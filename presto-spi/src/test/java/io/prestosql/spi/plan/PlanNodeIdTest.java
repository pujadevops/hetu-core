/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.spi.plan;

import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class PlanNodeIdTest
{
    private PlanNodeId planNodeIdUnderTest;

    @BeforeMethod
    public void setUp() throws Exception
    {
        planNodeIdUnderTest = new PlanNodeId("id");
    }

    @Test
    public void testToString() throws Exception
    {
        assertEquals("id", planNodeIdUnderTest.toString());
    }

    @Test
    public void testEquals() throws Exception
    {
        // Setup
        // Run the test
        final boolean result = planNodeIdUnderTest.equals("o");

        // Verify the results
        assertTrue(result);
    }

    @Test
    public void testHashCode() throws Exception
    {
        // Setup
        // Run the test
        final int result = planNodeIdUnderTest.hashCode();

        // Verify the results
        assertEquals(0, result);
    }
}
