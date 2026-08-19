# SpaceNoteWaterfall project guidance

## Current scope

SpaceNoteWaterfall is a PICO Spatial SDK 0.13.3 Android/Kotlin starter app with
package `com.example.spacenote`. It uses the planar `DefaultWindowContainer`
template and a portrait 960 x 1200 dp window as the base for a vertical
note-waterfall drag-and-drop experience.

The current milestone implements local capture, paced sorting, result editing,
category adjustment, persistent last-result restore, and screenshot workflow.
Screenshots are published through MediaStore to `DCIM/SpaceNoteWaterfall` and
opened through the system image-viewer intent so PICO Gallery indexes and shows
the saved image immediately. Sorting exposes drag-to-basket plus four explicit
ray-click category actions. Today, Later, Delegate, and Undecided each own an
independent task list; every visible category total must be derived directly
from those list sizes, never from a separately maintained count field.
The paced scheduler is keyed only by page, active note, and pending note IDs;
do not add its internal `waiting` flag to `LaunchedEffect` keys because that
self-cancels the two-second release delay.
When the pending queue becomes empty, sorting remains on screen and all basket
notes stay reclaimable. Only the explicit `查看结果` action enters Results, and
`返回分拣` restores the same four category lists for further adjustment.

## Structure

- `app/src/main/java/com/example/spacenote/Main.kt`: declares the root planar
  container and wraps its Compose tree in `PicoTheme`.
- `app/src/main/java/com/example/spacenote/content/HomePage.kt`: complete
  portrait-oriented capture, sorting, results, and local screenshot flow.
- `app/src/main/java/com/example/spacenote/platform/SpatialApplication.kt`:
  starts the Spatial app DSL.
- `app/src/main/java/com/example/spacenote/platform/LaunchActivity.kt`: launcher
  activity used by the PICO runtime.
- `app/src/main/AndroidManifest.xml`: planar window identity, portrait size,
  resize behavior, and system Material.Regular glass configuration.
- `app/src/androidTest`: package and launcher-resolution checks. Its custom
  `SpatialTestRunner` substitutes a plain `Application` in the instrumentation
  process because booting `SpatialApplication` there terminates the runner.
  Runtime liveness is verified externally with `pico-cli app launch` and
  `app info`; launching `SpatialLaunchActivity` inside `ActivityScenario`
  terminates instrumentation on the PICO emulator.

## UI and Spatial SDK rules

- Build every 2D interface with SpatialUI APIs from
  `com.pico.spatial.ui.design.*`; prefer built-in components.
- Wrap every root container UI tree in `PicoTheme`.
- Use `PicoTheme.colorScheme` and `PicoTheme.typography` roles. Do not add
  Material or Material3 components, themes, or dependencies.
- Keep the current `DefaultWindowContainer` system glass enabled through
  `pico.spatial.windowcontainer.materialbackground=1`; do not paint a solid
  root background over it.
- Use `Modifier.spatialHoverEffect` for any future custom hover visuals.
- Preserve the planar portrait container unless a later requirement explicitly
  changes the window model.

## Natural next increments

1. Add a note data model and a vertically scrolling SpatialUI note list.
2. Add reorder/drop targets and spatial hover/haptic feedback for note dragging.
3. Persist note content and order, then add emulator interaction tests.

## Build and run

```powershell
.\gradlew.bat assembleDebug
.\gradlew.bat installDebug
pico-cli app launch com.example.spacenote --activity .platform.LaunchActivity
.\gradlew.bat connectedAndroidTest
```
