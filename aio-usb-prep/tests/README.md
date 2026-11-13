# USB selection and cleanup simulation

Run from the repository root with PowerShell 7 or Windows PowerShell 5.1:

```powershell
./aio-usb-prep/tests/WindowsUsbSelection.Sim.ps1
```

The script loads `../tools/WindowsUsbVolume.ps1`, replaces disk, partition and
volume discovery with fake data, and restricts cleanup to a newly created folder
inside the operating system's temporary directory. It does not open, format,
mount, select or write to an actual USB volume. It deletes only its own temporary
folder when finished. The symbolic-link cases report SKIP if symlink creation is
unavailable or requires additional permissions.

The simulation checks USB selection exclusions, device identity changes,
preflight cleanup and protected metadata, symbolic links and paths outside the
selected root, and installer-file and hash validation. It does not exercise
Windows volume GUID paths, Win32 `CreateDirectoryW`, Windows storage APIs,
physical USB hardware, or a vehicle installation. Those require separate native
Windows and vehicle tests.
