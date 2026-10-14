# Reimora — Java Terminal Edition

An offline student reimbursement and advance-settlement project for the Object-Oriented Programming Laboratory.

**Student:** Pratik Deepak Phapale · **PRN:** 125B1F210  
**College:** Pimpri Chinchwad College of Engineering, Pune  
**Department:** Information Technology · Second Year · Semester III · Division B  
**Faculty:** Tanuja Patankar

## Run on Windows

Open this folder in a terminal and run:

```powershell
.\run.bat
```

Requires JDK 17 or later. No Maven, internet, API keys, or external libraries are needed. The script compiles the Java source before launching it.

On the first run, choose a password for the main administrator, `pratik.phapale25@pccoepune.org`. There is no preconfigured administrator password. Password entry is hidden when the terminal supports Java Console; otherwise the application tells you that input is visible.

Run checks with:

```powershell
.\test.bat
```

## Features

- Local login with salted PBKDF2 password hashes.
- Student and approver access requests, admin approval/rejection, branch-based reviewer assignment, account suspension, and audit history.
- All nine PCCOE B.Tech branches in a numbered selector.
- Saved beneficiaries with encrypted account identifiers and masked displays.
- Draft claims, multiple expense items, multiple receipt/UPI attachments per expense, correction/resubmission, withdrawal, partial approvals and rejection.
- Duplicate attachment/reference warnings and documented reviewer acknowledgement.
- Advances, reserved allocations, partial reimbursements, partial returns, and unallocated-advance returns, using `BigDecimal`.
- CSV reports and printable text claim summaries.
- Persistent in-app notifications and a background worker that writes email-style messages to a local mailbox. **These are local files, not sent emails.**
- Optional fictional demo accounts/data, created from the administrator menu.
- Text-receipt import with editable fields and keyword category suggestions. **This is not AI/OCR.** Image/PDF attachments are stored, not visually scanned.
- Atomic local persistence, previous-state backup, and a lock preventing two application instances from editing the same data.

## Suggested demonstration

1. Complete the administrator setup and use **Create demo data**. Choose the demo password when prompted.
2. Log in as `student.demo@example.test`; create a draft, import `samples/stationery-receipt.txt`, and attach that same file as receipt evidence.
3. Select the demo advance, apply INR 2,000, and submit the INR 2,650 claim.
4. Log in as `approver.demo@example.test`, inspect the claim and approve INR 2,650.
5. Log in as the administrator and record the INR 650 reimbursement with a fictional payment reference. The claim becomes settled.
6. Inspect notifications and exported reports. Register another student to demonstrate pending access and admin approval.

Demo email addresses do not deliver mail. Demo transactions and bank profiles are fictional.

## Data and limitations

Runtime files are kept in `data/`: `state.bin`, `state.bin.bak`, `master.key`, `receipts/`, and `mailbox/`. Reports are in `reports/`. Preserve the **entire data folder** together when backing up; the encryption key is needed for existing beneficiary records. Do not commit it to a public repository.

The key is local to the same computer. This academic application is not a hardened multi-user banking service. It does not connect to banks, transfer money, send external emails, perform Google login, or scan images with AI. A payment is an administrator's record of an external/simulated transaction, not independent bank verification.

If data is unreadable, the application stops instead of silently resetting it. Close the application before restoring a known-good backup. Keep the damaged files for investigation. The previous-state backup may not include the most recent operation.

See `docs/OOP-MAPPING.md` and `docs/PROJECT-REPORT.md` for the design, syllabus mapping, and viva preparation.
