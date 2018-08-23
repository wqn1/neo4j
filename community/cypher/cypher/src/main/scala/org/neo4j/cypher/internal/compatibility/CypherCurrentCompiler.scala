/*
 * Copyright (c) 2002-2018 "Neo4j,"
 * Neo4j Sweden AB [http://neo4j.com]
 *
 * This file is part of Neo4j.
 *
 * Neo4j is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.neo4j.cypher.internal.compatibility

import org.neo4j.cypher.exceptionHandler.runSafely
import org.neo4j.cypher.internal._
import org.neo4j.cypher.internal.compatibility.v3_5.ExceptionTranslatingQueryContext
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.executionplan.{StandardInternalExecutionResult, ExecutionPlan => ExecutionPlan_v3_5}
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.helpers.InternalWrapping.asKernelNotification
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.profiler.PlanDescriptionBuilder
import org.neo4j.cypher.internal.compatibility.v3_5.runtime.{ExplainExecutionResult, RuntimeName}
import org.neo4j.cypher.internal.compiler.v3_5.phases.LogicalPlanState
import org.neo4j.cypher.internal.javacompat.ExecutionResult
import org.neo4j.cypher.internal.planner.v3_5.spi.PlanningAttributes.Cardinalities
import org.neo4j.cypher.internal.runtime.interpreted.TransactionBoundQueryContext.IndexSearchMonitor
import org.neo4j.cypher.internal.runtime.interpreted.{TransactionBoundQueryContext, TransactionalContextWrapper}
import org.neo4j.cypher.internal.runtime.{ExecutableQuery => _, _}
import org.neo4j.cypher.internal.v3_5.logical.plans._
import org.neo4j.cypher.{CypherException, CypherExecutionMode}
import org.neo4j.graphdb.{Notification, Result}
import org.neo4j.kernel.api.query.{CompilerInfo, ExplicitIndexUsage, SchemaIndexUsage}
import org.neo4j.kernel.impl.query.{QueryExecutionMonitor, TransactionalContext}
import org.neo4j.kernel.monitoring.{Monitors => KernelMonitors}
import org.neo4j.values.virtual.MapValue
import org.opencypher.v9_0.frontend.PlannerName
import org.opencypher.v9_0.frontend.phases.{CompilationPhaseTracer, RecordingNotificationLogger}
import org.opencypher.v9_0.util.TaskCloser

import scala.collection.JavaConverters._

/**
  * Composite [[Compiler]], which uses a [[CypherPlanner]] and [[CypherRuntime]] to compile
  * a preparsed query into a [[ExecutableQuery]].
  *
  * @param planner the planner
  * @param runtime the runtime
  * @param contextCreator the runtime context creator
  * @param kernelMonitors monitors support
  * @tparam CONTEXT type of runtime context used
  */
case class CypherCurrentCompiler[CONTEXT <: RuntimeContext](planner: CypherPlanner,
                                                            runtime: CypherRuntime[CONTEXT],
                                                            contextCreator: RuntimeContextCreator[CONTEXT],
                                                            kernelMonitors: KernelMonitors
                                                           ) extends org.neo4j.cypher.internal.Compiler {

  /**
    * Compile [[PreParsedQuery]] into [[ExecutableQuery]].
    *
    * @param preParsedQuery          pre-parsed query to convert
    * @param tracer                  compilation tracer to which events of the compilation process are reported
    * @param preParsingNotifications notifications from pre-parsing
    * @param transactionalContext    transactional context to use during compilation (in logical and physical planning)
    * @throws CypherException public cypher exceptions on compilation problems
    * @return a compiled and executable query
    */
  override def compile(preParsedQuery: PreParsedQuery,
                       tracer: CompilationPhaseTracer,
                       preParsingNotifications: Set[Notification],
                       transactionalContext: TransactionalContext,
                       params: MapValue
                      ): ExecutableQuery = {

    val planningNotificationLogger = new RecordingNotificationLogger(Some(preParsedQuery.offset))

    val logicalPlanResult =
      planner.parseAndPlan(preParsedQuery, tracer, planningNotificationLogger, transactionalContext, params)

    val planState = logicalPlanResult.logicalPlanState
    val logicalPlan = planState.logicalPlan
    val queryType = getQueryType(planState)

    val runtimeContext = contextCreator.create(logicalPlanResult.plannerContext.notificationLogger,
                                               logicalPlanResult.plannerContext.planContext,
                                               logicalPlanResult.plannerContext.clock,
                                               logicalPlanResult.plannerContext.debugOptions,
                                               queryType == READ_ONLY)

    val executionPlan3_5 = runtime.compileToExecutable(planState, runtimeContext)

    new CypherExecutableQuery(
      logicalPlan,
      runtimeContext.readOnly,
      logicalPlanResult.logicalPlanState.cardinalities,
      executionPlan3_5,
      preParsingNotifications,
      planningNotificationLogger.notifications.map(asKernelNotification(planningNotificationLogger.offset)),
      logicalPlanResult.reusability,
      logicalPlanResult.paramNames,
      logicalPlanResult.extractedParams,
      buildCompilerInfo(logicalPlan, planState.plannerName, executionPlan3_5.runtimeName),
      planState.plannerName,
      queryType)
  }

  private def buildCompilerInfo(logicalPlan: LogicalPlan,
                                plannerName: PlannerName,
                                runtimeName: RuntimeName): CompilerInfo =

    new CompilerInfo(plannerName.name, runtimeName.name, logicalPlan.indexUsage.map {
      case SchemaIndexSeekUsage(identifier, labelId, label, propertyKeys) => new SchemaIndexUsage(identifier, labelId, label, propertyKeys: _*)
      case SchemaIndexScanUsage(identifier, labelId, label, propertyKey) => new SchemaIndexUsage(identifier, labelId, label, propertyKey)
      case ExplicitNodeIndexUsage(identifier, index) => new ExplicitIndexUsage(identifier, "NODE", index)
      case ExplicitRelationshipIndexUsage(identifier, index) => new ExplicitIndexUsage(identifier, "RELATIONSHIP", index)
    }.asJava)

  private def getQueryType(planState: LogicalPlanState): InternalQueryType = {
    val procedureOrSchema = ProcedureCallOrSchemaCommandRuntime.queryType(planState.logicalPlan)
    if (procedureOrSchema.isDefined) // check this first, because if this is true solveds will be empty
      procedureOrSchema.get
    else if (planState.solveds(planState.logicalPlan.id).readOnly)
      READ_ONLY
    else if (columnNames(planState.logicalPlan).isEmpty)
      WRITE
    else
      READ_WRITE
  }

  private def columnNames(logicalPlan: LogicalPlan): Array[String] =
    logicalPlan match {
      case produceResult: ProduceResult => produceResult.columns.toArray

      case procedureCall: StandAloneProcedureCall =>
        procedureCall.signature.outputSignature.map(_.seq.map(_.name).toArray).getOrElse(Array.empty)

      case _ => Array()
    }

  protected class CypherExecutableQuery(logicalPlan: LogicalPlan,
                                        readOnly: Boolean,
                                        cardinalities: Cardinalities,
                                        executionPlan: ExecutionPlan_v3_5,
                                        preParsingNotifications: Set[Notification],
                                        planningNotifications: Set[Notification],
                                        reusabilityState: ReusabilityState,
                                        override val paramNames: Seq[String],
                                        override val extractedParams: MapValue,
                                        override val compilerInfo: CompilerInfo,
                                        plannerName: PlannerName,
                                        queryType: InternalQueryType) extends ExecutableQuery {

    private val searchMonitor = kernelMonitors.newMonitor(classOf[IndexSearchMonitor])

    private def getQueryContext(transactionalContext: TransactionalContext) = {
      val ctx = new TransactionBoundQueryContext(TransactionalContextWrapper(transactionalContext))(searchMonitor)
      new ExceptionTranslatingQueryContext(ctx)
    }

    def execute(transactionalContext: TransactionalContext, executionMode: CypherExecutionMode,
                params: MapValue): Result = {
      val innerExecutionMode = executionMode match {
        case CypherExecutionMode.explain => ExplainMode
        case CypherExecutionMode.profile => ProfileMode
        case CypherExecutionMode.normal => NormalMode
      }
      runSafely {

        val queryContext = getQueryContext(transactionalContext)

        val planDescriptionBuilder =
          new PlanDescriptionBuilder(logicalPlan, plannerName, readOnly, cardinalities, executionPlan.runtimeName, executionPlan.metadata)

        val taskCloser = new TaskCloser
        taskCloser.addTask(queryContext.transactionalContext.close)
        taskCloser.addTask(queryContext.resources.close)

        val internalExecutionResult =
          if (innerExecutionMode == ExplainMode) {
            taskCloser.close(success = true)
            val columns = columnNames(logicalPlan)

            ExplainExecutionResult(columns,
                                   planDescriptionBuilder.explain(),
                                   queryType,
                                   preParsingNotifications ++ planningNotifications)
          } else {

            val doProfile = innerExecutionMode == ProfileMode
            val runtimeResult = executionPlan.run(queryContext, doProfile, params)

            new StandardInternalExecutionResult(queryContext,
                                                executionPlan.runtimeName,
                                                runtimeResult,
                                                taskCloser,
                                                queryType,
                                                preParsingNotifications,
                                                innerExecutionMode,
                                                planDescriptionBuilder)
          }

        new ExecutionResult(
          ClosingExecutionResult.wrapAndInitiate(
            transactionalContext.executingQuery(),
            internalExecutionResult,
            runSafely,
            kernelMonitors.newMonitor(classOf[QueryExecutionMonitor])
          )
        )
      }
    }

    def reusabilityState(lastCommittedTxId: () => Long, ctx: TransactionalContext): ReusabilityState = reusabilityState
  }

}

