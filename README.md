# Combat Data

Client-side Fabric mod that records your sword PvP fights and shows statistics and charts in game.
It only observes: nothing is sent over the network. Builds for Minecraft 26.1.2, 26.2 and 1.21.11.

## Features

- Rolling buffer (1–120 min) with a REC label on the HUD; F9 saves it, fights can also be saved automatically.
- Hits, misses, reach, aim, crits, jump reset, W-tap, combos, trades and 3 block hits.
- Charts, per-fight breakdown, top-down replay, progress and comparison across recordings.
- HUD indicators (health, reach, combo, ping, charge, jump reset) and a fight summary card.
- Opponent behaviour: signs that an opponent's hits do not add up, with the cases and honest causes like lag.
  An estimate from client data, not proof.
- Server profiles: jump reset window, knockback and reach per server.
- Sharing: export a recording or one fight as a file, or as a code that fits in a chat message.
- Themes, Russian and English, search across the menu (Ctrl+F). Works with VulkanMod.

## Controls

| Key | Action |
|---|---|
| Right Shift | Open the menu |
| F9 | Save the buffer |
| unbound | Marker, reset the buffer, indicators on/off, recording on/off |

Commands: `/combatdata open | save | marker | reset confirm | record on|off|toggle`.
With [Mod Menu](https://modrinth.com/mod/modmenu) the menu also opens from the mod list.

## Building

Requires JDK 25.

```
./gradlew build      # builds the mod and runs core tests
./gradlew runClient  # starts the game with the mod
```

The jar is written to `build/libs/`. Recordings are stored in `.minecraft/combatdata/recordings/`,
settings in `config/combatdata.json` and `config/combatdata-servers.json`.

## License

MIT, see [LICENSE](LICENSE).
