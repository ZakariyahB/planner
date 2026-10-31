# Contract change requests

The contracts are the files marked "Contracts (Stream 0)" in `android/engine`, `android/calendar` and `android/app/.../di`.
A stream that needs a different type or signature does not edit them: it appends an entry here on its branch, and the
master agent applies accepted changes when merging.

Format:

```
## YYYY-MM-DD · Stream X · short title
- What:
- Why:
- Affects: streams
- Proposed change: (code or signature)
- Status: proposed | accepted | rejected (by master)
```

---
