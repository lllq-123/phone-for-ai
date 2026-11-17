package dev.phoneforai.companion;

import org.junit.Test;
import static org.junit.Assert.*;

public final class PhoneCommandPolicyTest {
    @Test public void userControlLeavesReadOnlyCommandsAvailable(){
        for(String type:new String[]{"screen.capture","ui.dump","files.list","files.read","apps.list"})
            assertFalse(type,PhoneCommandPolicy.mutates(type));
    }
    @Test public void actionsAndUnknownCommandsRequireReleasedControl(){
        for(String type:new String[]{"screen.wake","app.launch","clipboard.set","ui.type","ui.tap",
                "ui.long_press","ui.swipe","ui.global.home","ui.global.back","ui.global.recents",
                "root.exec","files.write","unknown"})assertTrue(type,PhoneCommandPolicy.mutates(type));
    }
}
