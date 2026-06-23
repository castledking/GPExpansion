<p align="center">
<img src="https://repository-images.githubusercontent.com/1126629614/19884d9d-e1cc-40ce-88e5-774ba811e5d7" alt="" />
</p>

# <p align="center"> Extend GriefPrevention with rental signs, mailboxes, and more </p>

<p align="center">
    <a href="https://modrinth.com/plugin/gpexpansion"><img alt="Download on Modrinth" src="https://img.shields.io/badge/Download%20on-Modrinth-1bd96a?style=for-the-badge&logo=modrinth&logoColor=white"></a>
    <a href="https://discord.com/invite/pCKdCX6nYr"><img src="https://img.shields.io/badge/Discord-Community-5865F2?style=for-the-badge&logo=discord&logoColor=white" alt="Discord Community" /></a>
    <a href="https://github.com/castledking/GPExpansion/issues"><img src="https://img.shields.io/badge/GitHub-Issues-181717?style=for-the-badge&logo=github" alt="GitHub Issues" /></a>
    <a href="https://github.com/castledking/GPExpansion/wiki"><img src="https://img.shields.io/badge/GitHub-Wiki-181717?style=for-the-badge&logo=github" alt="GitHub Wiki" /></a>
</p>

GPExpansion adds powerful features to [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) including **rental signs**, **claim mailboxes**, **global claims**, **rental snapshots**, **claim GUIs**, **sign protection**, and more — all while maintaining the self-service philosophy.

Haven't heard of GriefPrevention 3D Subdivisions? Get it [here](https://github.com/castledking/GriefPrevention3D) to enable mailbox support. This is a fork that replaces the GriefPrevention jar.

### <span style="color:#555555;">Supported Platforms</span>
**Spigot, Paper, Purpur, and Folia**

Requires [GriefPrevention](https://github.com/GriefPrevention/GriefPrevention) and optionally [Vault](https://github.com/MilkBowl/Vault) for economy features.

Optionally, replace GriefPrevention with [GriefPrevention3D](https://github.com/castledking/GriefPrevention3D) for seamless mailboxes (1x1x1 subdivisions and public container trust). Mailboxes will work with regular GP and there is a config setting called `mailbox-protocol: virtual` that helps if you want to be able to stack mailboxes on top of each other.

## <span style="color:#333333;">Features</span>

### Rental Signs
Allow claim owners to rent out their claims to other players using signs.
- Set rental prices and durations
- Automatic trust/untrust on rental start/expiry
- Supports Vault economy, experience, claim blocks, and item-based payments
- Interactive setup wizard with `/rentclaim` command
- **Rental Snapshots** – Save and restore claim state between renters

<div align="center">

<details>
<summary>Setting up a rental sign manually</summary>

<img src="https://castled.codes/assets/rent_simple.gif" alt="" />

</details>

</div>

### Sell Signs
Allow claim owners to sell their claims to other players using signs.
- Set claim prices
- Automatic transfer of ownership of claim
- Supports Vault economy, experience, claim blocks, and item-based payments
- Interactive setup wizard with `/sellclaim` command

<div align="center">

<details>
<summary>Setting up a sell sign manually</summary>

<img src="https://castled.codes/assets/sell_simple.gif" alt="" />

</details>

</div>

### Claim Mailboxes
Give each claim a mailbox where other players can deposit items.
- Owners have full access to retrieve items
- Non-owners see a virtual inventory (snapshot at open time)
  - Changes save only when the menu closes
  - Can take back items before closing (reversible deposits)
  - Items returned if mailbox fills up while viewing
- Chest opening sound/animation on sign click
- Storage warnings when mailbox is nearly full
- Purchasable via signs with configurable prices
- Interactive setup wizard with `/mailbox` command

<div align="center">

<details>
<summary>Creating a self mailbox</summary>

<img src="https://castled.codes/assets/self_mailbox.gif" alt="" />

</details>

<details>
<summary>Selling a mailbox to other players</summary>

<img src="https://castled.codes/assets/sell_mailbox_to_others.gif" alt="" />

</details>

<details>
<summary>Sharing a mailbox with other players</summary>

<img src="https://castled.codes/assets/share_mailbox.gif" alt="" />

</details>

</div>

### Self Mailboxes (Instant Creation)
- Owners and renters can place `[Mailbox]` wall signs directly on containers in claims they own or rent
- Configurable limit via `defaults.max-self-mailboxes-per-claim`

| Protocol Type | Subdivision Created | Non-Owner View | Live Updates | Stackable |
| --- | --- | --- | --- | --- |
| Real Protocol | 1x1x1 (GP3D) or 1x1 2D (regular GP) | Real chest with public container trust | Yes | No |
| Virtual Protocol | None | Virtual snapshot inventory | No (snapshot at open time) | Yes |

### Global Claims
Allow claims to be listed in a global claim list, viewable to all.
- Set global settings like icon, description, name, spawn point, and more
- Allows users to teleport to global claims via GUI by default
- Allows `[global]` claim signs to instantly set spawn and list as global
- Simple `/claim global <true|false>` command to manage global listing status

### Sign Protection
Protect your rental and mailbox signs from unauthorized modification.
- Admin-only sign breaking for active rentals
- Automatic cleanup on sign removal

### Rental Restoration Snapshots
Admins with `griefprevention.restoresnapshot` can save and restore claim block state for rentals. When a rental or eviction ends, the claim can be restored to a saved snapshot so the next renter sees the original build.

- **Create** – Save the current claim blocks as a snapshot (e.g., before listing for rent)
- **List** – View snapshots for a claim or for all claims
- **Restore** – On eviction or rental end, the latest snapshot is applied automatically

### Commands
- `/claim snapshot create [claimId]` – Create a snapshot
- `/claim snapshot list [claimId|all]` – List snapshots
- `/claim snapshot remove <snapshotId>` – Delete a snapshot

### Claim Management & GUIs
Interactive claim management with fully configurable GUIs:
- `/claim name <name>` - Set claim name (supports color codes with permissions)
- `/claim desc <description>` - Set claim description (uses same color permissions as name)
- `/claim icon <material>` - Set claim icon for GUI display
- `/claim spawn` - Set the teleport spawn point for your claim
- `/claim tp [claimId]` - Teleport to a claim's spawn point
- `/claim flags [claimId]` - Open the claim flags GUI (GPFlags)
- `/claim options [claimId]` - Open the claim options GUI
- `/claim ban <player>` - Ban players from your claims
- `/claim unban <player>` - Unban players from your claims
- `/claim info [claimId]` - View detailed claim information
- `/mailbox` - Manage your mailboxes
- `/gpx reload` - Reload configuration and language files
- `/gpx max` - Manage player sign creation limits

### Claim GUI Features
- Visual claim map editor – Resize, subdivide, and manage claims via interactive GUI
- Customizable layouts, icons, and permissions
- Teleport to claims, view claim info, and manage settings from one interface
- Integration with GPFlags for claim flag management

### Configurable Tax System
Optional tax system for claim maintenance:
- Configurable tax rates per claim block
- Multiple payment methods (money, claim blocks, experience)
- Grace periods before claim deletion
- Tax exemptions via permissions

### Claim Block Accruals
Permission-based claim block accrual profiles for different player groups:
- Configurable blocks per hour, maximum accrued blocks, and maximum claim limits
- Profiles match by Vault/LuckPerms group or custom permission nodes (`griefprevention.accruals.<group>`)
- Per-player overrides for fine-grained control
- Automatic application on login and claim creation

**Example configuration:**
```yaml
accruals:
  groups:
  - name: default
    blocks-per-hour: 100
    max-blocks: 80000
    max-claims: 0  # disabled
  - name: vip
    blocks-per-hour: 20
    max-blocks: 250000
    max-claims: 10
```

**Admin commands:**
- `/gpx accruals check [player]` – View effective limits for a player
- `/gpx accruals group <group> set|add|remove <per-hour|max-blocks|max-claims|all> <amount>` – Edit group settings
- `/gpx accruals player <name> set|add|remove|reset <per-hour|max-blocks|max-claims|all> [amount]` – Manage player overrides
- `/gpx accruals creategroup <name> [blocks-per-hour] [max-blocks] [max-claims] [permission]` – Create new group
- `/gpx accruals deletegroup <name>` – Delete a group

### <span style="color:#555555;">Documentation</span>
**Sign formats, configuration, permissions, and detailed guides**
[All here](https://github.com/castledking/GPExpansion/blob/main/README.md)

### <span style="color:#555555;">Support</span>

- [Issue Tracker](https://github.com/castledking/GPExpansion/issues) - Report bugs or problems
- [Discussions](https://github.com/castledking/GPExpansion/discussions) - Feature requests and questions
- [Wiki](https://github.com/castledking/GPExpansion/wiki) - Detailed documentation and guides
- [Discord](https://discord.gg/pCKdCX6nYr) - Join our community

### <span style="color:#555555;">Building from Source</span>

```
git clone https://github.com/castledking/GPExpansion.git
cd GPExpansion
mvn clean install
```

The compiled jar will be in `target/`.

<div align="center"><i>Built to extend GriefPrevention with love ❤️</i></div>
