/*
 * Source++, the open-source live coding platform.
 * Copyright (C) 2022 CodeBrig, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package integration

import io.vertx.junit5.VertxTestContext
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation

@Suppress("unused")
class CyclicObjectLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    class TopObject {
        var bottom: BottomObject? = null
    }

    class BottomObject {
        var top: TopObject? = null
    }

    private fun cyclicObject() {
        startEntrySpan("cyclicObject")
        val cyclicObject = TopObject()
        cyclicObject.bottom = BottomObject()
        cyclicObject.bottom!!.top = cyclicObject
        addLineLabel("done") { Throwable().stackTrace[0].lineNumber }
        stopSpan()
    }

    @Test
    fun `cyclic object`() {
        setupLineLabels {
            cyclicObject()
        }

        val testContext = VertxTestContext()
        onBreakpointHit { bpHit ->
            testContext.verify {
                assertTrue(bpHit.stackTrace.elements.isNotEmpty())
                val topFrame = bpHit.stackTrace.elements.first()
                assertEquals(2, topFrame.variables.size)

                //cyclicObject
                val cyclicObject = topFrame.variables.first { it.name == "cyclicObject" }
                assertEquals(
                    "integration.CyclicObjectLiveBreakpointTest\$TopObject",
                    cyclicObject.liveClazz
                )
                val cyclicObjectId = cyclicObject.liveIdentity
                assertNotNull(cyclicObjectId)

                val bottomObject = (cyclicObject.value as List<Map<String, *>>).first()
                assertEquals(
                    "integration.CyclicObjectLiveBreakpointTest\$BottomObject",
                    bottomObject["liveClazz"]
                )

                val topObject = (bottomObject["value"] as List<Map<String, *>>).first()
                assertNotNull(topObject)
                assertEquals(
                    "integration.CyclicObjectLiveBreakpointTest\$TopObject",
                    topObject["liveClazz"]
                )

                val topObjectId = topObject["liveIdentity"] as String
                assertNotNull(topObjectId)
                assertEquals(cyclicObjectId, topObjectId)
            }

            //test passed
            testContext.completeNow()
        }.completionHandler {
            if (it.failed()) {
                testContext.failNow(it.cause())
                return@completionHandler
            }

            //add live breakpoint
            instrumentService.addLiveInstrument(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        CyclicObjectLiveBreakpointTest::class.qualifiedName!!,
                        getLineNumber("done"),
                        //"spp-test-probe" //todo: impl this so applyImmediately can be used
                    ),
                    //applyImmediately = true //todo: can't use applyImmediately
                )
            ).onSuccess {
                //trigger live breakpoint
                vertx.setTimer(5000) { //todo: have to wait since not applyImmediately
                    cyclicObject()
                }
            }.onFailure {
                testContext.failNow(it)
            }
        }

        errorOnTimeout(testContext)
    }
}
