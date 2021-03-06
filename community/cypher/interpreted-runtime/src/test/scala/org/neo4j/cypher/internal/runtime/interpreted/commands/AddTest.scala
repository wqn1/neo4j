/*
 * Copyright (c) 2002-2020 "Neo4j,"
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
package org.neo4j.cypher.internal.runtime.interpreted.commands

import java.nio.charset.StandardCharsets

import org.neo4j.cypher.internal.runtime.ExecutionContext
import org.neo4j.cypher.internal.runtime.interpreted.QueryStateHelper
import org.neo4j.cypher.internal.runtime.interpreted.commands.expressions.{Add, Literal, ParameterFromSlot}
import org.neo4j.cypher.internal.v4_0.util.test_helpers.CypherFunSuite
import org.neo4j.exceptions.CypherTypeException
import org.neo4j.values.storable.Values.{longValue, stringValue, utf8Value}
import org.neo4j.values.storable.{UTF8StringValue, Values}

class AddTest extends CypherFunSuite {

  val m = ExecutionContext.empty
  val s = QueryStateHelper.empty

  test("numbers") {
    val expr = Add(Literal(1), Literal(1))
    expr(m, s) should equal(longValue(2))
  }

  test("with_null") {
    val nullPlusOne = Add(Literal(null), Literal(1))
    val twoPlusNull = Add(Literal(2), Literal(null))

    nullPlusOne(m, s) should equal(Values.NO_VALUE)
    twoPlusNull(m, s) should equal(Values.NO_VALUE)
  }

  test("strings") {
    val expr = Add(Literal("hello"), Literal("world"))
    expr(m, s) should equal(stringValue("helloworld"))
  }

  test("stringPlusNumber") {
    val expr = Add(Literal("hello"), Literal(1))
    expr(m, s) should equal(stringValue("hello1"))
  }

  test("numberPlusString") {
    val expr = Add(Literal(1), Literal("world"))
    expr(m, s) should equal(stringValue("1world"))
  }

  test("numberPlusBool") {
    val expr = Add(Literal(1), Literal(true))
    intercept[CypherTypeException](expr(m, s))
  }

  test("UTF8 value addition") {
    // Given
    val hello = "hello".getBytes(StandardCharsets.UTF_8)
    val world = "world".getBytes(StandardCharsets.UTF_8)
    val state = QueryStateHelper.emptyWith(params = Array( utf8Value(hello), utf8Value(world)))

    // When
    val result = Add(ParameterFromSlot(0, "p1"), ParameterFromSlot(1, "p2"))(m, state)

    // Then
    result shouldBe a[UTF8StringValue]
    result should equal(utf8Value("helloworld".getBytes(StandardCharsets.UTF_8)))
    result should equal(Values.stringValue("helloworld"))
  }
}
