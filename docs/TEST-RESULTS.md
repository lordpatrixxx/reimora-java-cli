# Verification results

Verified on 14 October 2026 using the installed JDK 26.0.2.1, compiling with `--release 17`. Java 17 bytecode compatibility was checked by compilation; execution on a separate Java 17 installation was not performed.

## Automated integration checks

Command: `test.bat`

Result: **76 checks passed.**

Coverage includes registration and access approval, password hashing, duplicate identities, data ownership, reviewer scope, future dates, evidence linking, partial approval, preserved submission revisions, advance reservation/release, both settlement directions, partial payments, duplicate references, concurrent settlement attempts, suspension, report exports, notification failure/retry, restart persistence, file locking and corrupt-data preservation.

Tests use temporary workspaces and fictional records. They do not initialize the administrator or application data in the real project folder and do not send external emails.

## Terminal workflow test

Exercised the actual terminal menus using scripted standard input:

1. First-run administrator setup and login.
2. Optional demo account creation.
3. Student login and text-receipt import with attachment.
4. INR 2,000 advance selection and INR 2,650 claim submission.
5. Assigned approver login and approval.
6. Administrator login and INR 650 settlement.
7. Text summary and CSV export.
8. Local mailbox file generation.
9. Restart, student login and verification of the settled claim.

Result: **Passed**, with no unexpected application or worker errors. A local transcript is available in `build/cli-smoke.log` until generated build files are removed.

## Deliberate exclusions

External email delivery, Google login, bank verification, actual funds transfer and image AI/OCR were not tested because this offline edition does not implement those services. Interactive hidden password entry depends on Java Console availability; piped-input testing used the visible-input fallback.
