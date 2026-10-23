# Combat Data

Client-side Fabric mod for Minecraft 26.1.2 that keeps a rolling recording of sword PvP fights and shows
statistics and charts in game. It only observes: nothing is sent over the network.

## Features

- Rolling buffer (1–120 min) with a REC indicator on the HUD; each fight is saved automatically when it ends,
  F9 saves the buffer manually.
- Hit tracking: confirmed hits, misses with their cause, reach, aim offset, crits and sweeps.
- Jump reset, W-tap, combos, trades and 3 block hit analysis.
- Charts, per-fight breakdown, top-down fight replay and progress across recordings.
- Optional HUD indicators, each movable and resizable: exact health, reach of the last hit, combo, ping,
  attack charge, jump reset timing and 3 block hits (with note block sounds), session totals. They show only
  your own data about what already happened.
- Fight summary card on the HUD, themes, Russian and English interface.
- Works with VulkanMod: everything is drawn with standard GUI fills and text.

## Controls

| Key | Action |
|---|---|
| Right Shift | Open the menu |
| F9 | Save the buffer |
| unbound | Add a marker, reset the buffer, show or hide indicators |

Commands: `/combatdata open`, `/combatdata save`, `/combatdata marker`, `/combatdata reset confirm`.

## Building

Requires JDK 25.

```
./gradlew build      # builds the mod and runs core tests
./gradlew runClient  # starts the game with the mod
```

The jar is written to `build/libs/`. Recordings are stored in `.minecraft/combatdata/recordings/`, settings in
`config/combatdata.json`.

## License

MIT, see [LICENSE](LICENSE).
