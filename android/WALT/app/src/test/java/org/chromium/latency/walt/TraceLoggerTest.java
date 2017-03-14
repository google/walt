/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.chromium.latency.walt;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;

import static junit.framework.Assert.assertTrue;
import static org.mockito.Mockito.when;
import static org.powermock.api.mockito.PowerMockito.mockStatic;

@RunWith(PowerMockRunner.class)
@PrepareForTest(android.os.Process.class)
public class TraceLoggerTest {

    @Test
    public void testLogText() {
        final TraceLogger traceLogger = TraceLogger.getInstance();
        traceLogger.log(30012345, 30045678, "SomeTitle", "Some description here");
        traceLogger.log(40012345, 40045678, "AnotherTitle", "Another description here");
        mockStatic(android.os.Process.class);
        when(android.os.Process.myPid()).thenReturn(42);
        String expected =
                "WALTThread-[0-9]+ \\(42\\) \\[[0-9]+] .{4} 30\\.012345: tracing_mark_write: B\\|42\\|SomeTitle\\|description=Some description here\\|WALT\n" +
                "WALTThread-[0-9]+ \\(42\\) \\[[0-9]+] .{4} 30\\.045678: tracing_mark_write: E\\|42\\|SomeTitle\\|\\|WALT\n" +
                "WALTThread-[0-9]+ \\(42\\) \\[[0-9]+] .{4} 40\\.012345: tracing_mark_write: B\\|42\\|AnotherTitle\\|description=Another description here\\|WALT\n" +
                "WALTThread-[0-9]+ \\(42\\) \\[[0-9]+] .{4} 40\\.045678: tracing_mark_write: E\\|42\\|AnotherTitle\\|\\|WALT\n";
        assertTrue(traceLogger.getLogText().matches(expected));
    }
}
