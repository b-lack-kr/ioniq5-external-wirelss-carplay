# Windows USB regression test

Run from the repository root in an **administrator** Windows PowerShell 5.1 or PowerShell 7 terminal:

```powershell
./usb-prep/tests/test_windows_volume.ps1 -Script ./usb-prep/source/tools/WindowsUsbVolume.ps1
```

The test creates two disposable 128 MB VHD files in a unique temporary directory, attaches and formats only those virtual disks as FAT32/exFAT, and detaches them in `finally`. It never selects or formats a physical USB. A failed detach leaves the temporary directory for inspection.

It executes the production writer through real volume GUID paths. Storage discovery is substituted with a guard that checks the test VHD association before each write phase. Assertions cover the original Windows PowerShell 5.1 exception, root and nested Unicode filenames, corrupt input rejected before cleanup, an interrupted copy without an installer descriptor, retry, repeated writes, hashes, preserved system metadata and unchanged volume identity. This is native Windows filesystem coverage, not physical USB hardware or vehicle validation.

The production helper uses `CreateDirectoryW` for one directory component at a time. Windows PowerShell's .NET Framework directory creation can walk above the GUID root, and `GetDirectoryName` drops the root's required final separator. Drive-letter replacement is intentionally avoided so writes stay bound to the selected volume.

References: [Windows volume naming](https://learn.microsoft.com/en-us/windows/win32/fileio/naming-a-volume), [.NET Framework directory implementation](https://github.com/microsoft/referencesource/blob/main/mscorlib/system/io/directory.cs).
