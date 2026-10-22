# Sofatap App Store metadata

This is the canonical `asc metadata` directory for iPhone version **1.0**, build **15**. English is the primary locale. The public name is Sofatap in every language.

| App/site language | App Store locale |
| --- | --- |
| English | en-US |
| Simplified Chinese | zh-Hans |
| Japanese | ja |
| Spanish | es-ES |
| French | fr-FR |
| German | de-DE |
| Brazilian Portuguese | pt-BR |
| Korean | ko |
| Arabic | ar-SA |
| Hindi | hi |
| Russian | ru |
| Indonesian | id |

The 24 JSON files contain app name, subtitle, privacy URL, description, keywords, promotional text, support URL and marketing URL. They omit `whatsNew` for the initial release and contain no review contact, pricing, territory, release-mode or legal-agreement changes. Translation lengths and field formats have been checked; independent native-language editorial review remains pending.

The draft describes the shipped manual desktop controls and explains conditional live editing, on-device dictation, computer requirements and the experimental LG webOS preview. It does not advertise autonomous AI operation, universal TV mouse support or lock-screen unlocking. [Build-specific review notes](../app-store-1.0-15-review-notes.txt) give reviewers the exact pairing path and receiver version.

Screenshots and previews are managed separately. Apple can inherit primary-language images when a localization is added; inherited English images are not localized images. Use actual build-15 device captures and inspect each resulting locale before review. [Apple localization guidance](https://developer.apple.com/help/app-store-connect/manage-app-information/localize-app-information).

## Validate and inspect a draft update

Run from the repository root:

```sh
asc metadata validate --dir docs/app-store/metadata --output json
asc metadata push --app 6822188472 \
  --app-info fedc6f5f-1afd-4162-8160-c0100ba98632 \
  --version 1.0 --platform IOS \
  --dir docs/app-store/metadata --dry-run --output json
```

Review the differences, then omit `--dry-run` to save the draft. Do not add `--allow-deletes`. These files do not change screenshots, privacy questionnaire publication, price, availability or release type. Read back the saved metadata and run `asc validate` afterward.

During the October 14 staging run, this CLI's metadata lookup omitted version locales that a paginated localization list returned. If a plan proposes creating an existing locale, use `asc localizations list --version VERSION_ID --paginate` and update the existing locale with `asc localizations update --version VERSION_ID --locale LOCALE` and the intended fields. Verify all 12 locales through the paginated API result; do not delete or recreate them to make a plan pass.

## Build and review details

Build 15 is `0b55fb0b-cd25-4893-8037-0b014a9719cd`. The existing version is `91096e3e-ef6c-4da6-8e3d-64e22c760a25`.

```sh
asc versions attach-build \
  --version-id 91096e3e-ef6c-4da6-8e3d-64e22c760a25 \
  --build 0b55fb0b-cd25-4893-8037-0b014a9719cd --output json
```

The approved review contact is maintained privately, outside the repository. Copy the existing approved Sofatap TestFlight contact into the formal App Store review detail; do not invent or publish contact values. `asc review details-update` accepts `--contact-first-name`, `--contact-last-name`, `--contact-email`, `--contact-phone`, `--demo-account-required=false` and `--notes`. It does not offer a notes-file option. Use a subprocess argument list to read the notes file and private contact JSON without shell interpolation or logging those values.

Saving these drafts and attaching a build do not submit the app for review. Do not run a submit command as part of metadata preparation. See the [current readiness audit](../readiness-1.0-15.md) for remaining requirements.
