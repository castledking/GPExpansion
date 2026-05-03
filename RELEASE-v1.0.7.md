# GPExpansion v1.0.7

## Highlights

- **Timed Claim Flight** — Ported claim flight from the GriefPrevention3D-ClaimFly fork and expanded it into a time-bank based toggle feature.
- **Claim Flight Admin Tools** — Added `/claimfly` management commands for staff.
- **PlaceholderAPI Support** — Added claim flight time/status placeholders.
- **Config Repair** — Fixed malformed `player-commands` handling and added automatic `claimfly.use` insertion.

## New Features

### Timed Claim Flight

Claim flight now grants flight only when the player has all required conditions:

- **Permission**: `griefprevention.claimfly.use` (default: `true`)
- **Available time**: player must have claim flight time remaining
- **Toggle enabled**: player must have claim flight enabled with `/claimfly` or `/claim fly`
- **Claim access**: player must be inside a claim they have access/trust in
- **Gamemode**: Survival or Adventure only

When active, remaining claim flight time is consumed while the player is receiving claim flight.

### Player Commands

- `/claimfly` — Toggles personal claim flight on/off
- `/claim fly` — Alias through the main `/claim` command

Players without remaining claim flight time cannot enable the toggle.

### Admin Commands

Staff with `gpx.admin` can manage online players' claim flight time:

- `/claimfly add <player|all|*> <time>`
- `/claimfly check <player|all|*>`
- `/claimfly reset <player|all|*>`
- `/claimfly take <player|all|*> <time>`
- `/claimfly set <player|all|*> <time>`

Supported time examples:

- `30m`
- `1h`
- `1h20m12s`
- `1d`

### PlaceholderAPI

Added placeholders:

- `%claim_flight_time%` — Remaining claim flight time, formatted like `1h 20m 12s` or `0s`
- `%claim_flight%` — Returns `yes` if the player has claim flight time remaining, otherwise `no`

### Claim Flight Safety

- Automatically revokes claim flight when leaving a valid claim or running out of time
- Applies slow falling for 5 seconds if the player was actively flying when flight is revoked
- Periodic reconciler runs every 2 seconds to catch:
  - Gamemode changes
  - Permission revocation
  - Trust/access changes
  - Time expiration
- Tracks only flight granted by claimfly so other plugin flight sources are not stripped

## Fixes

- Fixed `PlayerJoinEvent` `NullPointerException` caused by storing null claim values in a `ConcurrentHashMap`
- Fixed `player-commands` parsing so hyphenated permission names like `sign.create.self-mailbox` are not split incorrectly
- Disabled the config normalization routine that could corrupt YAML indentation for `claim.toggleglobal.1`
- Added automatic ensure logic for `claimfly.use` in existing `player-commands` sections

## Migration

The default `config.yml` now includes:

```yaml
player-commands:
  - claimfly.use
```

Existing installs will automatically append `claimfly.use` to `player-commands` if it is missing. This grants `griefprevention.claimfly.use` through the dynamic `gpx.player` permission path.

Claim flight balances and toggle states are stored in:

```text
plugins/GPExpansion/claim-flight.yml
```

## Technical Notes

- Uses GPBridge for reflection-based GriefPrevention API access
- Folia-compatible via SchedulerAdapter
- Uses a persistent claim flight manager for player time balances and toggle states
- Only applies to Survival and Adventure gamemodes
- Creative and Spectator gamemodes manage their own flight and are ignored by claimfly
