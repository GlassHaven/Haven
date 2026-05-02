# Bundled Font Attribution

Haven ships **Hack Nerd Font Mono Regular** as the default terminal
typeface. The file lives at
`core/ui/src/main/res/font/hack_nerd_font_mono_regular.ttf`.

The font is the Hack base font passed through the Nerd Fonts icon
patcher. Each component carries its own license; this NOTICE collects
the upstream attributions so distributors of Haven (including F-Droid)
can verify them in one place. All licenses below are compatible with
Haven's AGPL-3.0.

## Hack base font

- **License**: MIT (with Bitstream Vera attribution clause)
- **Source**: <https://github.com/source-foundry/Hack>
- **Upstream license file**:
  <https://github.com/source-foundry/Hack/blob/master/LICENSE.md>

## Nerd Fonts patcher

- **License**: MIT
- **Source**: <https://github.com/ryanoasis/nerd-fonts>
- **Upstream license file**:
  <https://github.com/ryanoasis/nerd-fonts/blob/master/LICENSE>

## Patched-in icon glyph blocks

The patched font contains glyphs from the following upstream icon sets,
each under its own license:

| Block | License | Upstream |
|---|---|---|
| Devicons | MIT | <https://github.com/vorillaz/devicons> |
| Font Awesome (Free) | SIL OFL 1.1 (font outlines) + CC-BY 4.0 (icons) | <https://github.com/FortAwesome/Font-Awesome> |
| Octicons | MIT | <https://github.com/primer/octicons> |
| Material Design Icons | Apache-2.0 | <https://github.com/Templarian/MaterialDesign> |
| Codicons | MIT | <https://github.com/microsoft/vscode-codicons> |
| Powerline Symbols | MIT | <https://github.com/ryanoasis/powerline-extra-symbols> |
| Weather Icons | SIL OFL 1.1 | <https://github.com/erikflowers/weather-icons> |
| Seti UI | MIT | <https://github.com/jesseweed/seti-ui> |
| Pomicons | MIT | <https://github.com/gabrielelana/pomicons> |
| IEC Power Symbols | Public Domain | <https://unicodepowersymbol.com> |

### Required attribution

The CC-BY 4.0 component (Font Awesome icons) requires visible
attribution. Haven satisfies this by listing "Hack Nerd Font Mono" with
the composite license text in the in-app About dialog
(`Settings → About Haven`) and by shipping this file in source.

## Updating the font

To replace the bundled font with a newer Nerd Fonts release:

1. Download `HackNerdFontMono-Regular.ttf` from
   <https://github.com/ryanoasis/nerd-fonts/releases>.
2. Replace `core/ui/src/main/res/font/hack_nerd_font_mono_regular.ttf`.
3. Bump the version note in this file if a glyph block was added or its
   license changed.
4. Rebuild and verify the About dialog still credits the font correctly.

If you replace the bundled font with a different Nerd Font (e.g.
JetBrains Mono Nerd Font), update the resource name, the
`TerminalScreen` resource reference, and the `AboutDialog` credits to
reflect the new base font's license.
