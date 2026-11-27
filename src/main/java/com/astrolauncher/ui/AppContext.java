package com.astrolauncher.ui;

import com.astrolauncher.auth.MicrosoftAuthService;
import com.astrolauncher.model.LauncherSettings;
import com.astrolauncher.mods.ModManager;
import com.astrolauncher.repository.AccountRepository;
import com.astrolauncher.repository.SettingsRepository;
import com.astrolauncher.service.CurseForgeService;
import com.astrolauncher.service.LaunchService;
import com.astrolauncher.service.ModrinthService;
import com.astrolauncher.update.UpdateChecker;
import com.astrolauncher.update.UpdateDownloader;

/**
 * Holds the shared, long-lived services pages need (settings, launch
 * pipeline) so each page doesn't spin up its own thread pools or duplicate
 * state. Created once in AstroLauncherApplication and passed down to
 * MainWindow and its pages.
 */
public class AppContext {
    public final LauncherSettings settings;
    public final SettingsRepository settingsRepository;
    public final LaunchService launchService;
    public final ModrinthService modrinthService;
    public final CurseForgeService curseForgeService;
    public final ModManager modManager;
    public final AccountRepository accountRepository;
    public MicrosoftAuthService microsoftAuthService; // rebuilt when msClientId changes in Settings
    public final UpdateChecker updateChecker;
    public final UpdateDownloader updateDownloader;

    public AppContext(LauncherSettings settings, SettingsRepository settingsRepository, LaunchService launchService,
                       ModrinthService modrinthService, CurseForgeService curseForgeService,
                       ModManager modManager, AccountRepository accountRepository,
                       MicrosoftAuthService microsoftAuthService, UpdateChecker updateChecker,
                       UpdateDownloader updateDownloader) {
        this.settings = settings;
        this.settingsRepository = settingsRepository;
        this.launchService = launchService;
        this.modrinthService = modrinthService;
        this.curseForgeService = curseForgeService;
        this.modManager = modManager;
        this.accountRepository = accountRepository;
        this.microsoftAuthService = microsoftAuthService;
        this.updateChecker = updateChecker;
        this.updateDownloader = updateDownloader;
    }
}
