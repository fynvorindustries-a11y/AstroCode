package com.averolauncher.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.averolauncher.model.LauncherSettings;
import org.junit.jupiter.api.Test;

class ShellStateMigrationTest {

    @Test
    void existingProfileGetsPinnedSidebarOnce() {
        LauncherSettings s = new LauncherSettings();
        s.pinSidebar = false;
        s.violetLookApplied = false;

        ShellState.bind(s, null);
        assertTrue(ShellState.isPinned(), "existing profile should be pinned after the one-shot migration");
        assertTrue(s.violetLookApplied);
    }

    @Test
    void playerWhoUnpinsStaysUnpinnedAfterRestart() {
        LauncherSettings s = new LauncherSettings();
        s.violetLookApplied = true;      // migration already ran on a previous start
        s.pinSidebar = false;            // player turned it off in Settings

        ShellState.bind(s, null);
        assertFalse(ShellState.isPinned(), "migration must not override a later choice");
    }
}
