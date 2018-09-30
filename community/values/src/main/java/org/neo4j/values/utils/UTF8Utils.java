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
package org.neo4j.values.utils;

import org.neo4j.values.storable.TextValue;
import org.neo4j.values.storable.UTF8StringValue;

import static org.neo4j.values.storable.Values.utf8Value;

/**
 * Utility class for operations on utf-8 values.
 */
public final class UTF8Utils
{
    private UTF8Utils()
    {
        throw new UnsupportedOperationException( "Do not instantiate" );
    }

    /**
     * Add two values.
     * @param a value to add
     * @param b value to add
     * @return the value a + b
     */
    public static TextValue add( UTF8StringValue a, UTF8StringValue b )
    {
        int offsetA = a.offset();
        int lengthA = a.length();
        byte[] bytesA = a.bytes();
        int offsetB = b.offset();
        int lengthB = b.length();
        byte[] bytesB = b.bytes();

        byte[] bytes = new byte[lengthA + lengthB];
        System.arraycopy( bytesA, offsetA, bytes, 0, lengthA );
        System.arraycopy( bytesB, offsetB, bytes, lengthA, lengthB );
        return utf8Value( bytes );
    }
}
