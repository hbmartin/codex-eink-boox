# E-Ink UI

`eink-ui` is Codex Eink's vendor-neutral Jetpack Compose design system for Android e-ink displays.
It contains the base controls and adaptive layouts plus presentation-only primitives for Codex timelines,
connection state, approvals, diffs, and terminal output. The module deliberately has no dependency on the
app or protocol modules.

## Attribution

The base design system was copied from the [`eink-ui` module in
`hbmartin/onyx-boox-screensaver-gol`](https://github.com/hbmartin/onyx-boox-screensaver-gol/tree/16f1e6188a3f0dd45641ba60effa55ff2c7fc26b/eink-ui)
at commit `16f1e6188a3f0dd45641ba60effa55ff2c7fc26b`, then extended for Codex Eink.
The source and this derivative are available under the repository's MIT license.

## Principles

- Every state remains understandable in black and white; color never carries meaning.
- Components draw opaque surfaces with persistent outlines and no ripple, elevation, shadow, or authored animation.
- Interactive controls provide at least 48dp targets.
- Public state is controlled by callers. Disclosure helpers never own protocol or approval state.
- `EinkAdaptivePaneLayout` switches to two panes at 840dp when sufficient height is available.

## Theme

```kotlin
EinkTheme {
    EinkButton(onClick = ::save, emphasis = EinkButtonEmphasis.Strong) {
        Text("Save")
    }
}
```

`EinkColors`, `EinkTypography`, `EinkSpacing`, `EinkBorders`, `EinkShapes`, and
`EinkLayoutTokens` can be replaced independently. The default theme is light-only.

## Components

- Actions: `EinkButton`, `EinkIconButton`, `EinkFloatingActionButton`, `EinkLink`
- Selection and forms: `EinkCheckboxRow`, `EinkSwitchRow`, `EinkRadioGroup`, `EinkTextField`
- Structure: `EinkSurface`, `EinkCard`, `EinkExpandableSection`, `EinkAdaptivePaneLayout`
- Codex presentation: `EinkStatusBadge`, `EinkTimelineRow`, `EinkTimelineCard`,
  `EinkConnectionBanner`, `EinkApprovalPanelShell`, `EinkDiffBlock`, and
  `EinkTerminalDisclosure`

Codex presentation primitives accept strings and composable slots instead of protocol models. The app maps
its domain state into these components, which keeps this module independently previewable and testable.
