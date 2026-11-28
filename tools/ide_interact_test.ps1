# IDE interaction test for jdtls-native on OpenIDEA
# Simulates: Ctrl+Click navigation, hover, code typing with completion
param(
    [string]$Mode = "full"
)

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing

Add-Type @'
using System;
using System.Runtime.InteropServices;
public class WinInput {
    [DllImport("user32.dll")] public static extern bool SetCursorPos(int x, int y);
    [DllImport("user32.dll")] public static extern void mouse_event(uint dwFlags, uint dx, uint dy, uint cButtons, uint dwExtraInfo);
    [DllImport("user32.dll")] public static extern bool SetForegroundWindow(IntPtr hWnd);
    [DllImport("user32.dll")] public static extern void keybd_event(byte bVk, byte bScan, uint dwFlags, uint dwExtraInfo);
    [DllImport("user32.dll")] public static extern int GetForegroundWindow();
    public const uint MOUSEEVENTF_LEFTDOWN = 0x02;
    public const uint MOUSEEVENTF_LEFTUP = 0x04;
    public const uint KEYEVENTF_KEYUP = 0x02;
}
'@

function Screenshot([string]$path) {
    Add-Type -AssemblyName System.Drawing
    $bounds = [System.Windows.Forms.Screen]::PrimaryScreen.Bounds
    $bmp = New-Object System.Drawing.Bitmap($bounds.Width, $bounds.Height)
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.CopyFromScreen(0, 0, 0, 0, $bmp.Size)
    $g.Dispose()
    $bmp.Save($path, [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "screenshot: $path"
}

function Click([int]$x, [int]$y, [bool]$ctrl = $false) {
    [WinInput]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 100
    if ($ctrl) { [WinInput]::keybd_event(0x11, 0, 0, 0) }  # Ctrl down
    [WinInput]::mouse_event([WinInput]::MOUSEEVENTF_LEFTDOWN, 0, 0, 0, 0)
    Start-Sleep -Milliseconds 50
    [WinInput]::mouse_event([WinInput]::MOUSEEVENTF_LEFTUP, 0, 0, 0, 0)
    if ($ctrl) { [WinInput]::keybd_event(0x11, 0, [WinInput]::KEYEVENTF_KEYUP, 0) }  # Ctrl up
    Start-Sleep -Milliseconds 300
}

function Hover([int]$x, [int]$y) {
    [WinInput]::SetCursorPos($x, $y) | Out-Null
    Start-Sleep -Milliseconds 1500  # wait for hover popup
}

function TypeText([string]$text) {
    [System.Windows.Forms.SendKeys]::SendWait($text)
    Start-Sleep -Milliseconds 300
}

# Activate OpenIDEA
$proc = Get-Process -Name 'openidea*' -ErrorAction SilentlyContinue | Where-Object { $_.MainWindowTitle -ne '' } | Select-Object -First 1
if (-not $proc) { Write-Host "ERROR: OpenIDEA not found"; exit 1 }
[WinInput]::SetForegroundWindow($proc.MainWindowHandle) | Out-Null
Start-Sleep -Milliseconds 500
Write-Host "OpenIDEA activated: pid=$($proc.Id) title='$($proc.MainWindowTitle)'"

$screenshotDir = "$env:TEMP\jdtls-native-ide-test"
New-Item -ItemType Directory -Path $screenshotDir -Force | Out-Null

# Take initial screenshot to see current state
Screenshot "$screenshotDir\00_initial.png"

Write-Host "`n=== Test 1: Ctrl+Click on MessageDigest (JDK class) ==="
# The current file is MessageDigest.java tab - but we need to be in HmacSigner.java
# Let's click on the HmacSigner.java tab first
# From the screenshot, HmacSigner.java tab is at approximately x=1195, y=31
Click 1195 31
Start-Sleep -Milliseconds 500
Screenshot "$screenshotDir\01_hmac_signer_tab.png"

Write-Host "`n=== Test 2: Ctrl+Click on MessageDigest import (go to JDK source) ==="
# In HmacSigner.java, the import is at approximately line 3, "MessageDigest" at ~x=560, y=74
# Actually we need to know the exact editor coordinates. Let's use the text caret approach.
# The editor area starts at about x=540, y=60. Line 3 (import) would be at about y=74.
Click 560 74 $true  # Ctrl+Click on MessageDigest import
Start-Sleep -Milliseconds 1000
Screenshot "$screenshotDir\02_goto_jdk.png"
Write-Host "Ctrl+Click on MessageDigest done — check if JDK source opened"

Write-Host "`n=== Test 3: Navigate back (Ctrl+Alt+Left) ==="
[WinInput]::keybd_event(0x11, 0, 0, 0)  # Ctrl down
[WinInput]::keybd_event(0x12, 0, 0, 0)  # Alt down  
[WinInput]::keybd_event(0x25, 0, 0, 0)  # Left down
[WinInput]::keybd_event(0x25, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # Left up
[WinInput]::keybd_event(0x12, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # Alt up
[WinInput]::keybd_event(0x11, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # Ctrl up
Start-Sleep -Milliseconds 500
Write-Host "Navigated back"

Write-Host "`n=== Test 4: Hover on Algorithm ==="
# Algorithm.java tab - click on the tab first
# From screenshot, Algorithm.java tab is at approximately x=1447, y=31
Click 1447 31
Start-Sleep -Milliseconds 500
# Now hover over "Algorithm" in the class declaration
# The file content should show Algorithm class - hover on the class name
Hover 700 200
Screenshot "$screenshotDir\03_hover_algorithm.png"
Write-Host "Hover on Algorithm done — check if hover popup appeared"

Write-Host "`n=== Test 5: Type code to trigger completion ==="
# Go to end of file and start typing to trigger completion
# Press Ctrl+End to go to end of file
[WinInput]::keybd_event(0x11, 0, 0, 0)  # Ctrl down
[WinInput]::keybd_event(0x23, 0, 0, 0)  # End down
[WinInput]::keybd_event(0x23, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # End up
[WinInput]::keybd_event(0x11, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # Ctrl up
Start-Sleep -Milliseconds 200
# Press Enter to create a new line
[WinInput]::keybd_event(0x0D, 0, 0, 0)  # Enter down
[WinInput]::keybd_event(0x0D, 0, [WinInput]::KEYEVENTF_KEYUP, 0)  # Enter up
Start-Sleep -Milliseconds 200
# Type "Sys" to trigger completion
TypeText "Sys"
Start-Sleep -Milliseconds 1500  # wait for completion popup
Screenshot "$screenshotDir\04_completion_popup.png"
Write-Host "Typed 'Sys' — check if completion popup appeared"
# Press Escape to dismiss completion
[WinInput]::keybd_event(0x1B, 0, 0, 0)  # Escape
[WinInput]::keybd_event(0x1B, 0, [WinInput]::KEYEVENTF_KEYUP, 0)
Start-Sleep -Milliseconds 200

Write-Host "`n=== Test 6: Hover on a method call ==="
# Go back to HmacSigner.java tab
Click 1195 31
Start-Sleep -Milliseconds 500
# Hover over a method call in the code
Hover 620 350
Screenshot "$screenshotDir\05_hover_method.png"
Write-Host "Hover on method call done"

Write-Host "`n=== All screenshots saved to $screenshotDir ==="
Write-Host "Files:"
Get-ChildItem $screenshotDir | ForEach-Object { Write-Host "  $($_.Name) ($($_.Length) bytes)" }
