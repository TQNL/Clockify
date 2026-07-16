# Clockify custom changes

This document records the custom work made after cloning Fossify Clock and before publishing the project as Clockify.

- Upstream project: `FossifyOrg/Clock`
- Upstream base commit: `ab75e21893536ee50ce323bd76f6d5d70b5f0aa9`
- Documentation date: 2026-07-16

## Summary

The current branch adds an initial alarm-folder experience, a next-alarm summary above the alarm list, configurable upcoming-alarm notifications, and several alarm reliability fixes. The changes are concentrated around the alarm data model, database, alarm list, notification scheduling, and settings screen.

## User-visible changes

### Alarm folders

- A create-folder action appears in the alarm toolbar.
- Created folders are displayed before uncategorized alarms in the main alarm list.
- Selecting a folder opens a dedicated folder screen.
- The folder screen lists its alarms and can create a new alarm directly inside that folder.
- Folder rows show their alarm count.
- Folder names are limited to 30 characters.

The folder implementation is currently a foundation, not a complete organizer. The final working state does not yet expose controls for renaming or deleting folders, moving an existing alarm into a folder, or removing an alarm from a folder. Folder records are also not included as first-class entries in exported backups; only the alarm's optional `folderId` is serialized. Those gaps should be addressed before calling the feature complete.

### Next-alarm hero

- The alarm tab now shows a Samsung-style summary above the list.
- It displays a live countdown and the next alarm's date and time.
- It refreshes once per minute.
- It considers both enabled app alarms and the Android system's next alarm clock, allowing a snoozed alarm to be reflected.
- When no alarm is scheduled, the hero reports that no alarms were found.

### Upcoming-alarm notifications

- The notification lead time is now configurable in Settings.
- The default lead time changed from a hard-coded 10 minutes to 30 minutes.
- Upcoming notifications and their pending intents now use IDs derived from the alarm ID, preventing different alarms from replacing each other's notification.
- Stale upcoming notifications are cancelled when an alarm fires, is snoozed, is edited, is disabled, or is deleted.

### Reliability fixes

- Imported enabled alarms are rescheduled with Android's `AlarmManager` immediately after import, so they can fire without waiting for an app restart.
- The alarm UI no longer performs unsafe `Arrays$ArrayList` to `ArrayList` casts while rendering weekday labels.
- Backup JSON encoding tolerates unknown keys for better compatibility.

## Data and architecture changes

### Alarm model

`Alarm` and `ObfuscatedAlarm` now carry an optional `folderId`. Existing alarms remain uncategorized because the value defaults to `null`.

### SQLite schema

The alarm database version is now 4.

- The existing alarm table gains a nullable `folder_id` column.
- A `folders` table stores `id`, `name`, and `created_at`.
- Upgrade logic adds the new alarm column defensively so installations containing earlier experimental folder infrastructure can still migrate.
- `DBHelper` provides folder creation, lookup, rename, deletion, alarm lookup, and alarm-move primitives. Not all of these operations are exposed in the current UI.

### Main components

- `AlarmFragment` combines folder rows and uncategorized alarms and maintains the next-alarm hero.
- `FolderFragment` displays alarms belonging to one folder.
- `AlarmsAdapter` supports both `AlarmFolder` and `Alarm` row types while keeping selection and deletion limited to alarm rows.
- `MainActivity` hosts the folder fragment and handles folder back navigation.
- `Context` contains next-alarm calculation and per-alarm pending-intent scheduling.
- `SettingsActivity` exposes the notification lead time and reschedules imported alarms.

## Verification

The recovered working tree was checked with:

```powershell
.\gradlew.bat assembleDebug
```

The command completed successfully on 2026-07-16 and produced the `core`, `foss`, and `gplay` debug APK variants. Gradle reported only existing deprecated-API notes. `git diff --check` also completed without whitespace errors; Git only reported line-ending conversion notices on Windows.

## Suggested next work

1. Add folder rename and delete actions, with clear behavior for alarms in a deleted folder.
2. Add a move-to-folder control for existing alarms.
3. Export and import folder records, and remap folder IDs safely during import.
4. Add database migration and folder CRUD tests.
5. Exercise folder navigation, alarm scheduling, imports, and notification cleanup on a physical Android device.
