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

import io.vertx.kotlin.coroutines.await
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import spp.protocol.instrument.LiveBreakpoint
import spp.protocol.instrument.LiveSourceLocation
import java.util.concurrent.TimeUnit

@Suppress("unused")
class ExpiresAtLiveBreakpointTest : LiveInstrumentIntegrationTest() {

    @Test
    fun `expires at breakpoint`() = runBlocking {
        //add live breakpoint
        instrumentService.addLiveInstruments(
            listOf(
                LiveBreakpoint(
                    location = LiveSourceLocation(
                        "non-existent-class",
                        0,
                    ),
                    expiresAt = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(10)
                ),
            )
        ).await()

        //verify live breakpoint
        val breakpoint = instrumentService.getLiveInstruments(null).await()
        assertEquals(1, breakpoint.size)
        assertEquals("non-existent-class", breakpoint[0].location.source)

        //wait 15 seconds
        delay(TimeUnit.SECONDS.toMillis(15))

        //verify no live breakpoint
        val noBreakpoint = instrumentService.getLiveInstruments(null).await()
        assertEquals(0, noBreakpoint.size)
    }
}
