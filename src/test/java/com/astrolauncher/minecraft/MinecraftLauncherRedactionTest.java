package com.astrolauncher.minecraft;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Regression test for audit finding S-2: the Astro social token in -javaagent options was written to the log. */
class MinecraftLauncherRedactionTest {

    @Test
    void masksSessionArguments() {
        String out = MinecraftLauncher.redactSensitive(List.of(
                "java", "--username", "Steve", "--accessToken", "SECRET-MC-TOKEN", "--uuid", "1234abcd"));
        assertFalse(out.contains("SECRET-MC-TOKEN"));
        assertFalse(out.contains("1234abcd"));
        assertTrue(out.contains("Steve"), "non-sensitive arguments stay readable for debugging");
    }

    @Test
    void masksTheTokenInsideAJavaAgentOptionString() {
        String agent = "-javaagent:C:\\Users\\a\\agent.jar=api=https://x.example/api/v1,token=eyJhbGciOi.SECRET.sig,name=Steve,self=abc";
        String out = MinecraftLauncher.redactSensitive(List.of("java", agent, "net.minecraft.client.main.Main"));
        assertFalse(out.contains("eyJhbGciOi"), "agent token leaked: " + out);
        assertTrue(out.contains("token=<redacted>"));
        assertTrue(out.contains("name=Steve") && out.contains("api=https://x.example/api/v1"),
                "only the secret is removed, the rest of the option string is kept: " + out);
    }

    @Test
    void leavesAgentsWithoutSecretsAlone() {
        String agent = "-javaagent:agent.jar=api=https://x.example/api/v1,self=abc";
        assertEquals("java " + agent, MinecraftLauncher.redactSensitive(List.of("java", agent)));
    }
}
