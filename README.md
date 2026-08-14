# RefinePilot

Offline Android refinement assistant prototype for RAN Mobile.

## Safety-first MVP
- Select target enhancement (+7/+8/+9)
- Screen capture via Android MediaProjection
- Local enhancement-level recognition
- Accessibility gesture for the REFINE button
- Floating Pause/Stop overlay
- Stops at target, maximum attempts, uncertain detection, or capture/accessibility failure

## Build
GitHub Actions builds a debug APK on every push to `main`. Open the repository's **Actions** tab, select the latest **Build RefinePilot APK** run, then download the `RefinePilot-debug-apk` artifact.

Build pipeline initialized for v0.1.

> Test with disposable equipment first. Screen coordinates/detection are prototype-calibrated and may need calibration for your phone resolution. Automation may also be restricted by the game's rules.
