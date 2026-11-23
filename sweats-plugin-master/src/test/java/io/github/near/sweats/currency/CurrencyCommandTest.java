package io.github.near.sweats.currency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.near.sweats.TestCommandSenders;
import io.github.near.sweats.TestPlugins;
import io.github.near.sweats.permission.PermissionNodes;
import io.github.near.sweats.permission.PermissionService;
import io.github.near.sweats.rank.RankService;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CurrencyCommandTest {

    @TempDir
    Path folder;

    private final List<String> warnings = new ArrayList<>();

    @Test
    void onlyConsoleDevAndCmMayGive() {
        final RankService ranks = new RankService(folder, warnings::add);
        final List<Component> messages = new ArrayList<>();

        final CommandSender console = TestCommandSenders.console(true, messages);
        final CommandSender plain = TestCommandSenders.player("Plain", 0, false, messages);
        final CommandSender opPlayer = TestCommandSenders.opPlayer("OpPlayer", messages);

        // Console always authorized
        assertTrue(CurrencyCommand.authorized(ranks, console));
        // Plain player denied
        assertFalse(CurrencyCommand.authorized(ranks, plain));
        // OP player without DEV/CM rank denied
        assertFalse(CurrencyCommand.authorized(ranks, opPlayer));
    }

    @Test
    void cmAndDevRanksAreAuthorizedButAdminAndOwnerAreNot() {
        final RankService ranks = new RankService(folder, warnings::add);
        final UUID cm = UUID.randomUUID();
        final UUID dev = UUID.randomUUID();
        final UUID admin = UUID.randomUUID();
        final UUID owner = io.github.near.sweats.owner.Owner.ID;

        ranks.assign(cm, "Cm", "CM");
        ranks.assign(dev, "Dev", "DEV");
        ranks.assign(admin, "Admin", "Admin");
        // Owner has no rank assigned (Default)

        final List<Component> messages = new ArrayList<>();
        // CM and DEV ranks authorized
        assertTrue(CurrencyCommand.authorized(ranks, TestCommandSenders.playerWithUuid("CM", cm, messages)));
        assertTrue(CurrencyCommand.authorized(ranks, TestCommandSenders.playerWithUuid("DEV", dev, messages)));
        // Admin rank NOT authorized
        assertFalse(CurrencyCommand.authorized(ranks, TestCommandSenders.playerWithUuid("Admin", admin, messages)));
        // Owner without DEV/CM rank NOT authorized
        assertFalse(CurrencyCommand.authorized(ranks, TestCommandSenders.playerWithUuid("Owner", owner, messages)));
    }

    @Test
    void theCommandIsDeclaredNonOpByDefault() {
        final YamlConfiguration yaml = YamlConfiguration.loadConfiguration(
                new InputStreamReader(
                        getClass().getResourceAsStream("/plugin.yml"), StandardCharsets.UTF_8));

        assertEquals("false", yaml.getString("permissions.sweats.currency.give.default"));
        assertEquals("sweats.currency.give", yaml.getString("commands.currency.permission"));
    }

    @Test
    void currencyNamesAreCaseInsensitiveAndRejectUnknownValues() {
        assertEquals(Optional.of(CurrencyType.SWEAT_COINS), CurrencyType.parse("SweatCoins"));
        assertEquals(Optional.of(CurrencyType.SWEAT_COINS), CurrencyType.parse("sweat-coins"));
        assertEquals(Optional.of(CurrencyType.SWEAT_COINS), CurrencyType.parse("Sweat Coins"));
        assertEquals(Optional.of(CurrencyType.TRYHARD_SHARDS), CurrencyType.parse("tryhardshards"));
        assertEquals(Optional.of(CurrencyType.TRYHARD_SHARDS), CurrencyType.parse("TryHard Shards"));
        assertEquals(Optional.empty(), CurrencyType.parse("diamonds"));
        assertEquals(Optional.empty(), CurrencyType.parse(null));
    }
}
