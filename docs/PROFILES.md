# Profiles

Testlab's core knows nothing about your app. A **profile** tells it which app it is testing, where
the builds come from, which Jira project and labels a devtest uses, how the devtest comment looks,
which deeplinks exist, which test-account fields there are, and the vocabulary the autopilot needs.

The quickest start is the profiles page, **New profile from a repo** (`testlab open profiles`; or `testlab profile init --repo
owner/name --jira example.atlassian.net --read-packages`). It reads the repo's GitHub Actions
workflows and proposes a build type for each APK artifact of their newest successful runs:
- the artifact pattern, with commit hashes and versions made variable
- the branch prefixes and `workflow_dispatch` inputs from the workflow file
- the package and deeplink host, read from a downloaded APK

It then lists Jira's projects and suggests the queue and approved labels. You review the result
before it is saved. Destinations, the comment template and the login plan are then added in
`profile.json`.

A profile is a folder:

```
my-app/
├── profile.json        # everything below; no secrets, ever
├── skills/             # optional: playbooks that replace the core's (same name) or add the team's own
│   └── device-login/SKILL.md
└── plans/              # optional: plans, e.g. the login plan handsets_login runs
    └── login.json
```

Teams share it as a zip (the profiles page's Export / Import, or `testlab profile export` / `import`). Several profiles can be installed;
one is active per platform (one profile can cover both: see [below](#one-profile-for-android-and-ios)) (the profiles page, `testlab profile use ID`, or ask Claude:
`lab_list_profiles` / `lab_use_profile`). A running server only follows a switch made in it: after
`testlab profile use` in a terminal, restart `testlab service`, or let Claude switch. They live in `<data>/profiles/<id>/`. A branded build can also
bundle profiles (see [DEV.md](DEV.md#branded-builds)). Secrets stay in the vault: tokens are looked
up as `github.token@<owner>` / `jira.token@<site>` first and then as the plain `github.token` /
`jira.token`, so two profiles on the same Jira share one token.

The full schema is [profile.schema.json](profile.schema.json); [examples/example-shop](examples/example-shop)
is a complete one. Unknown fields are ignored, so a profile written for a newer app still loads.

## `profile.json`

```json
{
  "id": "my-app",
  "name": "My App",
  "description": "the My App Android app",
  "app": {
    "defaultPackage": "com.example.myapp.debug",
    "productionPackage": "com.example.myapp",
    "family": "com.example.myapp",
    "label": "My App"
  },
  "github": { "repo": "example-org/myapp-android", "org": "example-org" },
  "builds": [
    { "id": "pr", "label": "PR build", "workflowFile": "pr.yml", "artifact": "app-debug\\.apk",
      "packageName": "com.example.myapp.debug", "autoPrefixes": ["feature/", "fix/"], "default": true,
      "dispatchInputs": { "flavor": { "default": "dev", "choices": ["dev", "prod"] } } },
    { "id": "nightly", "label": "Nightly", "workflowFile": "nightly.yml", "artifact": "app-nightly\\.apk",
      "packageName": "com.example.myapp.nightly" }
  ],
  "releases": { "buildId": "nightly" },
  "tracker": {
    "site": "example.atlassian.net",
    "projects": ["APP"],
    "queueLabels": ["devtest"],
    "requiredLabels": [],
    "approvedLabel": "devtest-approved",
    "onApproved": { "addLabels": ["devtest-approved"], "removeLabels": [] },
    "onChanges": { "handBack": true }
  },
  "linking": { "branchPrefixes": [], "bareKeyBranches": true },
  "report": { "title": "Devtest", "language": "English", "dateFormat": "yyyy-MM-dd" },
  "devtest": { "teamCommand": "/devtest {key}", "teamCommandPaths": [".claude/commands/devtest.md"], "environments": ["staging", "prod"] },
  "navigation": {
    "host": "https://www.example.com",
    "destinations": {
      "search": { "path": "/search", "group": "pages" },
      "product": { "path": "/p", "group": "pages", "subpages": true },
      "home": { "tab": "nav_home", "group": "tabs" }
    },
    "aliases": { "start": "home" }
  },
  "autopilot": {
    "glossary": "Hjem=Home tab; Kurv=Cart",
    "labelLanguage": "Norwegian",
    "riskyWords": ["kjøp", "betal"],
    "leaveWords": ["tilbake", "lukk"]
  },
  "accounts": {
    "defaults": ["demo"],
    "fields": [ { "key": "username", "label": "Username" }, { "key": "password", "label": "Password" } ],
    "loginPlan": "login",
    "loginEnvironments": ["staging"],
    "loginDescription": "username + password on the staging backend",
    "isolateDuringLogin": false
  },
  "texts": { "builds.startCost": "A PR build takes a runner for 8 minutes and notifies the PR" }
}
```

## The sections

| Section | What it drives |
|---|---|
| `app` | The package plans, deeplinks and the login open (`defaultPackage`; a user can override it per computer with `device.packageOverride`). `productionPackage` is where purchase-worded taps are refused unless `device.allowRiskyOnProd` allows them. `family` is the package prefix whose other installed builds `isolate` disables during a login (each build claims the same return link). |
| `github` | The repo builds, PRs and releases come from. `org` is used for the SSO hint (defaults to the repo owner). `apiUrl` for GitHub Enterprise. |
| `builds` | One entry per kind of CI APK: the workflow that makes it, a regex for the artifact name, the package it installs as, the branch prefixes the workflow builds by itself (used to explain a missing build, and to find a ticket's branches), and `workflow_dispatch` inputs for «Start build». The `default` build is what a PR number means. iOS builds say `"target": "device"` (an `.ipa`, for real iPhones and iPads and, on Apple Silicon, this Mac) or `"simulator"` (a `.app`); a real device takes the first `device` type when no type is named. A build without `workflowFile` is looked for in every successful run on the branch (useful when several workflows make it). |
| `releases` | Which build type a GitHub release asset counts as. |
| `tracker` | Jira Cloud site and projects; the labels that put a ticket in the devtest queue (`queueLabels`, one of them), labels it must also have (`requiredLabels`, one of them, e.g. a review step that is passed or skipped), the approved label, and what «approved» / «changes needed» do (add/remove labels, hand the ticket back to the previous assignee). `queueJql` replaces the generated query (`{projects}`, `{labels}`, `{required}`, `{approved}` are filled in). |
| `linking` | Where a ticket's PRs are looked for besides Jira's development panel and PR titles: branches `{prefix}{KEY}…` (default: the builds' auto prefixes) and bare `{KEY}…`. |
| `report` | The devtest comment: a Markdown `template` (below), the `dateFormat`, the words for a CI build / release in the build line, the comment's `title` and the `language` Claude writes the items in. |
| `devtest` | The team's own Claude Code command for a devtest (`/devtest {key}`) and the paths that show a checkout has it; the environments offered for the comment. |
| `navigation` | `handsets_go` destinations: deeplink paths under `host` (or full URLs) and tabs reached by tapping a resource id; `subpages` for destinations that take one; aliases. |
| `autopilot` | The autopilot's vocabulary: a `glossary` sent with every question, the language labels are in, example sentences (`pickExample`, `doneExample`), a `pickNote`, and extra risky / leave-the-screen words (regex alternatives) on top of the core's English ones. |
| `accounts` | The test-account fields (the setup page shows one field per entry; plans use them as `{"cred": "<account>.<key>"}`), default account names, and the login plan `handsets_login` runs (`${account}`, `${package}`, `${env}`, `${Env}` are filled in). Any number of accounts can be added (the setup page, or `testlab accounts add NAME --note "what it is for"`); an account can log in on a platform when it has every `${account}.<field>` that platform's login plan types. `pools` are test-user pools: an account that is fetched fresh from a test service on every login instead of being saved by hand (below). |
| `texts` | Free texts: `builds.startCost` (what starting a build costs), `proxyman.cert`, `mcp.instructions` (extra notes for Claude, appended to the server instructions). |
| `testRepos` | Helper repos the team tests with, such as test management guides or an E2E test suite: `[{"id": "guides", "repo": "example-org/qa-guides"}, {"id": "e2e", "repo": "example-org/maestro-tests", "kind": "maestro"}]`. Testlab keeps a read-only copy of each (the branch's newest commit, `ref` for another branch or tag, fetched with the user's own GitHub token and checked at most hourly), adds their skills to the playbooks as `<id>:<name>` (from `skills`, default `.claude/skills` and a top `SKILL.md`), and gives Claude tools to list, read and search them; a `maestro` repo's flows are indexed with their test-case ids (`properties.testinyId`). Nobody has to work from inside those repos. `testlab test-repos` shows them. |
| `snapshotImages` | Where the app's screenshot tests keep their reference images, as globs (`["**/src/test/snapshots/images/**"]`). Empty = the usual places: Paparazzi's `snapshots/images`, committed Roborazzi `screenshots`, swift-snapshot-testing's `__Snapshots__`, iOSSnapshotTestCase's `ReferenceImages*`. `lab_pr_screens` shows the ones a PR changes, before and after, without building it. |
| `crashlytics` | Where the app's crashes in the field are: `{"apps": {"no.example.app": "1:1234567890:android:abc123"}, "timeframe": "7d"}`, the Firebase app id per package (`mobilesdk_app_id` in the app's google-services.json). Claude reads them with Google's Android CLI as the user's own Google account (`testlab android-cli login`): `lab_crashlytics_issues` (with `from_log=true` it looks up the crashes of the last log capture), `lab_crashlytics_issue`. Without a `package` the production package is used. `timeframe` (24h, 7d, 30d, 60d, 90d) is the default window. |
| `imports` | macOS Keychain services to offer importing tokens from: `{"keychain": {"com.example.tool": {"githubToken": "github.token"}}}`. |

## Test-user pools

When the backend has a test service that hands out test users, a pool account saves typing in
credentials and gives every login its own user:

```json
"accounts": {
  "pools": [{
    "name": "testpool",
    "url": "https://api.${env}.example.com/test-users/fetch",
    "headers": {"apiKey": "${key}"},
    "fields": {"user_id": "id"},
    "id": "user_id",
    "description": "a fresh test user per login (int and stage)"
  }],
  "poolLoginPlan": "pool-login"
}
```

`handsets_login(account="testpool", env="int")` asks the service for a user (the answer is one
user, or a list to pick from at random) and runs `poolLoginPlan` (else `loginPlan`); the plan reads
the user as `{"cred": "testpool.user_id"}`, like a saved account. Each device has its own pool
user. `fields` maps account fields to where they are in the answer (`id`, `user.id`, `items.0.id`);
`id` is the field that names the user: the result shows it as `pool_user`, and `user=<it>` logs the
same user in again without asking the service. The service key is a secret, never in the profile:
`testlab secrets set pool.testpool.key`, then put into the URL or a header as `${key}`.

An app that logs itself in from launch values (a test build reading the user from the start
intent) needs only the app start, cleared first:

```json
[
  {"open": "${package}/.MainActivity", "fresh": true,
   "args": ["--es", "environment", "${env}", "--es", "userId", {"cred": "${account}.user_id"}]},
  {"wait_for": "Home", "timeout": 25}
]
```

`fresh` clears the app's data first. On Android `args` are `am start` extras (`--es` text,
`--ez` true/false, `--ei` numbers); on iOS they are launch arguments.

## One profile for Android and iOS

A team with an app on both platforms can keep one profile. `platforms` holds, per platform, the
fields that differ, written as they would be at the top level; everything else is shared. Objects
merge field by field (so `tracker.projects` can differ while the site and labels are shared), lists
and values replace:

```json
{
  "id": "shop", "name": "Shop",
  "tracker": {"site": "example.atlassian.net", "queueLabels": ["devtest"]},
  "report": {"title": "Devtest"},
  "platforms": {
    "android": {
      "app": {"defaultPackage": "com.example.shop"},
      "github": {"repo": "example-org/shop-android"},
      "builds": [{"id": "pr", "label": "PR", "workflowFile": "pr.yml", "artifact": "app-debug\\.apk", "default": true}],
      "tracker": {"projects": ["AND"]}
    },
    "ios": {
      "app": {"defaultPackage": "com.example.Shop"},
      "github": {"repo": "example-org/shop-ios"},
      "builds": [{"id": "simulator", "label": "Simulator", "workflowFile": "simulator.yml", "artifact": "Shop.*\\.app", "default": true}],
      "tracker": {"projects": ["IOS"]}
    }
  }
}
```

Activating it makes it the profile of both platforms. Every call uses the platform it is about: the
device it names (an Android phone or an iOS Simulator), the ticket's project (AND-12 → Android,
IOS-7 → iOS), or `platform` on the GitHub and build tools. Calls for two devices can run at the same
time without reading each other's repo or login plan. `plans/ios/` and `skills/ios/` (or `android/`)
hold what differs per platform and win over the shared `plans/` and `skills/` by name.

The devtest queue lists both projects' tickets with their platform. With a phone and a Simulator
both free, each takes the next ticket for its platform (`jira_devtest_next(serial)`), so two
devtests run side by side; the queue shows which device is on which ticket (`testing_on`).

Editing the active profile on the profiles page writes a field back where it lives: into the platform's part
when the platform overrides it, else into the shared part.

## The devtest comment template

The comment is Markdown with Mustache-style tags. Values: `{{date}}`, `{{tester}}`, `{{build}}`,
`{{branch}}`, `{{env}}`, `{{result}}`, `{{report}}`, `{{approver}}`. Lists: `{{#tested}}…{{.}}…{{/tested}}`
and `{{#notTested}}…{{/notTested}}` repeat per item; `{{^tested}}…{{/tested}}` shows when the list is
empty; `{{#report}}…{{/report}}` only when a report is attached. A tag alone on a line takes its line
with it. End a line with two spaces for a line break inside a paragraph.

```markdown
## Devtest {{date}}

**Tested by:** {{tester}}  
**Build:** {{build}}

### Tested
{{#tested}}
- {{.}}
{{/tested}}

### Result
{{result}}

🤖 Written by Claude, approved by {{approver}}
```

## Checking a profile

The profiles page refuses a profile with problems and names each one (a regex that doesn't
compile, a destination without a path, a label with a space…). From a terminal:
`testlab profile check my-app/profile.json`.
