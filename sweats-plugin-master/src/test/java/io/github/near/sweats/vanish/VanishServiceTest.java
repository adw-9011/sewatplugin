package io.github.near.sweats.vanish;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.chat.OnlinePlayerNames;
import io.github.near.sweats.rank.RankService;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanishServiceTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();
    private RankService ranks;
    private VanishService vanish;

    @BeforeEach
    void setUp() {
        ranks = new RankService(folder, warnings::add);
        vanish = new VanishService(
                TestPlugins.plugin(), ranks, id -> true, new OnlinePlayerNames());
    }

    @Test
    void onlyOperatorsAndDevCanVanish() {
        final List<Component> messages = new ArrayList<>();
        final Player op = TestCommandSenders.opPlayer("Op", messages);
        final Player player = TestCommandSenders.player("Alex", 0, true, messages);

        assertTrue(vanish.canVanish(op));
        assertFalse(vanish.canVanish(player));

        ranks.assign(player.getUniqueId(), "Alex", "DEV");
        assertTrue(vanish.canVanish(player));
        assertTrue(vanish.canSeeVanished(player));

        ranks.assign(player.getUniqueId(), "Alex", "Admin");
        assertFalse(vanish.canVanish(player));
        assertFalse(vanish.canSeeVanished(player));
    }

    @Test
    void staffVisibilityIsOpOrTheDevRankOnly() {
        assertTrue(VanishService.isStaff(true, "default"));
        assertTrue(VanishService.isStaff(false, "dev"));
        assertFalse(VanishService.isStaff(false, "admin"));
        assertFalse(VanishService.isStaff(false, "sweat+"));
    }

    @Test
    void ordinaryViewersHideVanishedPlayersButStaffDoNot() {
        assertTrue(VanishService.shouldHide(false, true));
        assertFalse(VanishService.shouldHide(true, true));
        assertFalse(VanishService.shouldHide(false, false));
        assertFalse(VanishService.shouldHide(true, false));
    }

    @Test
    void vanishStateStartsEmpty() {
        assertFalse(vanish.isVanished(UUID.randomUUID()));
    }
}
