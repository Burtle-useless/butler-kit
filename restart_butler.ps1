# Restart the butler server from a detached process.
#
# Why this exists: a Claude Code session can be running as a CHILD of the
# butler server itself. If that session stops the server directly, it kills
# its own process tree halfway through and butler never comes back up.
#
# Pure ASCII on purpose: PowerShell 5.1 reads a BOM-less file as CP950 and
# silently mangles non-ASCII text.
param([switch]$Detached)

$ErrorActionPreference = 'Continue'

# Everything is derived from where this script sits (the repo root), so the
# kit works wherever it was cloned to.
$root = $PSScriptRoot
$log = "$root\restart.log"
$vbs = "$root\launch_butler.vbs"
$applog = "$root\butler.log"
$port = if ($env:BUTLER_PORT) { [int]$env:BUTLER_PORT } else { 47362 }
# Relative marker: any python whose command line mentions this repo's
# server\main.py is a butler root process.
$marker = "$root\server\main.py"

function W($m) {
    "$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')  $m" | Add-Content -Path $log -Encoding utf8
}

# Refuse to run when a Claude Code session INSIDE butler is the caller.
#
# The assistant runs inside butler; "restart the server to apply my change"
# would be the assistant killing itself mid-sentence, and the user sees the
# reply stop dead with no explanation. The system prompt already says not to
# do this (_RULE_NO_RESTART in engine/options.py) but prose cannot enforce
# it; the check has to be here.
#
# Seeing claude.exe on the parent chain is NOT enough: the desktop Claude
# Code app has claude.exe on its chain too, and it should be allowed to
# restart the server. The distinguishing fact is whether the chain ends up
# at butler's own python, so walk all the way and only then decide.
#
# The /restart endpoint spawns this script straight from the server process,
# so its chain reaches the marker without passing through claude.exe. That
# is the whole distinction -- nothing for the assistant to talk itself into.
function CalledFromClaude {
    $all = @{}
    Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        ForEach-Object { $all[[int]$_.ProcessId] = $_ }
    $cur = $PID
    $sawClaude = $false
    for ($i = 0; $i -lt 20; $i++) {
        $p = $all[[int]$cur]
        if (-not $p) { return $false }
        if ($p.Name -eq 'claude.exe' -or $p.Name -eq 'node.exe') { $sawClaude = $true }
        if ($p.Name -eq 'python.exe' -and $p.CommandLine -and
            $p.CommandLine -like "*$marker*") {
            # Reached butler itself. Only a refusal if a CC session sits
            # between here and it.
            return $sawClaude
        }
        $cur = [int]$p.ParentProcessId
        if (-not $cur) { return $false }
    }
    return $false
}

# Only meaningful on the first incarnation: after the WMI hand-off below the
# parent is WmiPrvSE and the chain tells us nothing.
if (-not $Detached -and (CalledFromClaude)) {
    W 'REFUSED: called from inside a Claude Code session'
    Write-Output 'Refused: you are running inside butler, so this would kill you mid-sentence.'
    Write-Output 'Tell the user to press restart on the tools page instead.'
    exit 1
}

# Self-detach unconditionally. If the caller is any descendant of the server,
# killing the server tears down the caller's job object, which reaps this
# script AND the fresh server it just spawned. A rule that matters must be
# enforced in code, not prose -- so the script re-launches itself through WMI
# (parent: WmiPrvSE, outside every job) no matter how it was invoked, and the
# first incarnation exits.
if (-not $Detached) {
    $me = $MyInvocation.MyCommand.Path
    $cmd = "powershell.exe -NoProfile -ExecutionPolicy Bypass -File `"$me`" -Detached"
    $r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{CommandLine=$cmd}
    if ($r.ReturnValue -eq 0) {
        Write-Output "restart handed off to detached pid $($r.ProcessId); watch restart.log"
        exit 0
    }
    # WMI refused (should not happen for the same user) -- carry on inline,
    # which is exactly the old risky behaviour, but better than doing nothing.
    Write-Output "WMI detach failed (rv=$($r.ReturnValue)); continuing inline"
}

function Listener {
    Get-NetTCPConnection -LocalPort $port -State Listen -ErrorAction SilentlyContinue |
        Select-Object -First 1
}

# Never kill these, whatever the process tree says. cloudflared is a
# long-lived tunnel often shared with other tools; taking it down here would
# break them silently.
$neverKill = @('cloudflared.exe')

# The root processes of a butler instance: matched on the command line, which
# is precise and cannot wander into unrelated python processes.
function ButlerRoots {
    Get-CimInstance Win32_Process -Filter "Name='python.exe' OR Name='pythonw.exe' OR Name='cmd.exe'" -ErrorAction SilentlyContinue |
        Where-Object { $_.CommandLine -and $_.CommandLine -like "*$marker*" }
}

# Every process in a butler instance -- the roots PLUS their whole descendant
# tree, leaves first.
#
# Why the tree and not a command-line match: butler spawns the Claude Code
# CLI, which spawns claude.exe, which spawns one process per MCP server.
# Only the root has the marker on its command line; every descendant inherits
# the log redirect handle, so leaving any of them alive keeps butler.log open
# and the log roll below fails.
#
# Leaves first so that killing a parent never leaves a child we can no longer
# reach through ParentProcessId.
function ButlerTree {
    $all = @(Get-CimInstance Win32_Process -ErrorAction SilentlyContinue |
        Select-Object ProcessId, ParentProcessId, Name)
    $out = New-Object System.Collections.ArrayList
    $seen = @{}
    $stack = @(ButlerRoots | ForEach-Object { $_.ProcessId })
    while ($stack.Count -gt 0) {
        # Pop the last element. Spelled out rather than $stack[0..($n-2)]:
        # with a single element left that range is 0..-1, which PowerShell
        # reads as the whole array -- an infinite loop instead of an empty
        # stack.
        $last = $stack.Count - 1
        $pid_ = $stack[$last]
        if ($last -eq 0) { $stack = @() } else { $stack = @($stack[0..($last - 1)]) }
        if ($seen.ContainsKey($pid_)) { continue }
        $seen[$pid_] = $true
        $p = $all | Where-Object { $_.ProcessId -eq $pid_ } | Select-Object -First 1
        if (-not $p) { continue }
        if ($neverKill -contains $p.Name) { continue }
        [void]$out.Add($p)
        foreach ($k in ($all | Where-Object { $_.ParentProcessId -eq $pid_ })) {
            if (-not $seen.ContainsKey($k.ProcessId)) { $stack += $k.ProcessId }
        }
    }
    $out.Reverse()
    return $out
}

W '--- restart requested ---'

# Wait for the assistant to finish talking before pulling the rug.
#
# The caller is very often the assistant running INSIDE butler; restarting
# always cuts off whoever asked for it, but being killed mid-sentence is
# avoidable. latest_seq bumps on every emitted event, so "it stopped
# changing" is a decent proxy for "nobody is streaming right now". Cap the
# wait -- a genuinely busy server would otherwise defer the restart forever.
function LatestSeq {
    $c = Listener
    if (-not $c) { return $null }
    try {
        (Invoke-RestMethod -Uri "http://$($c.LocalAddress):$port/v1/health" `
            -TimeoutSec 3).latest_seq
    } catch { $null }
}

$prev = LatestSeq
$quiet = 0
for ($i = 0; $i -lt 30; $i++) {          # up to ~60s
    Start-Sleep -Seconds 2
    $now = LatestSeq
    if ($now -eq $prev) {
        $quiet++
        if ($quiet -ge 2) { break }      # 4s with no new events
    } else {
        $quiet = 0
    }
    $prev = $now
}
if ($i -ge 30) { W 'still busy after 60s; restarting anyway' }

# Snapshot the pids BEFORE killing anything. Once the root is dead its
# children are orphans -- walking the tree again finds nothing, so the wait
# loop below has to watch this fixed list, not re-derive it.
$procs = @(ButlerTree)
$targets = @($procs | ForEach-Object { $_.ProcessId })

function AliveCount($ids) {
    if (-not $ids -or @($ids).Count -eq 0) { return 0 }
    return @(Get-Process -Id $ids -ErrorAction SilentlyContinue).Count
}

if ($procs.Count -gt 0) {
    # Names too, not just pids: when this goes wrong the log has to show WHAT
    # was killed.
    W ("stopping " + $procs.Count + " butler process(es): " +
        (($procs | ForEach-Object { "$($_.ProcessId)/$($_.Name)" }) -join ','))
    foreach ($p in $procs) {
        Stop-Process -Id $p.ProcessId -Force -ErrorAction SilentlyContinue
    }
} else {
    W 'no butler process found'
    # Fall back to whoever holds the port, in case main.py was started some
    # other way.
    $conn = Listener
    if ($conn) {
        W "stopping port holder pid=$($conn.OwningProcess)"
        Stop-Process -Id $conn.OwningProcess -Force -ErrorAction SilentlyContinue
    }
}

# Wait for BOTH the processes to die and the port to be released (up to 15s).
# The port matters most: the new instance binds it on startup and just exits
# if it is still taken.
for ($i = 0; $i -lt 30; $i++) {
    Start-Sleep -Milliseconds 500
    if (-not (Listener) -and (AliveCount $targets) -eq 0) { break }
}
if (Listener) { W 'WARNING: port still held after 15s' } else { W 'port released' }
$stragglers = AliveCount $targets
if ($stragglers -gt 0) {
    W ("WARNING: $stragglers process(es) still alive after 15s: " +
        ((Get-Process -Id $targets -ErrorAction SilentlyContinue |
            ForEach-Object { "$($_.Id)/$($_.ProcessName)" }) -join ','))
}

# Roll the app log so the new instance never fights the old handle for it.
# Do NOT swallow the failure: a silent failure here looks like "first launch
# dies without a trace". Retried because a handle can outlive its process by
# a moment, and a successful roll doubles as proof nobody holds the file.
if (Test-Path $applog) {
    $rolled = $false
    $err = ''
    for ($i = 0; $i -lt 20; $i++) {
        try {
            Move-Item -Path $applog -Destination "$root\butler.log.1" -Force -ErrorAction Stop
            $rolled = $true
            break
        } catch {
            $err = $_.Exception.Message
            Start-Sleep -Milliseconds 500
        }
    }
    if ($rolled) {
        W 'rolled butler.log -> butler.log.1'
    } else {
        W "WARNING: could not roll butler.log after 10s: $err"
    }
}

# Relaunch. Two paths:
#   1. If the user has a launch_butler.vbs in the repo root (the recommended
#      no-console-window autostart wrapper), use it -- it knows how they want
#      the server started.
#   2. Otherwise start python on server\main.py directly, preferring the
#      project venv, with output appended to butler.log.
function FindPython {
    foreach ($cand in @("$root\server\.venv\Scripts\python.exe",
                        "$root\server\venv\Scripts\python.exe")) {
        if (Test-Path $cand) { return $cand }
    }
    return 'python.exe'
}

function Launch($waitSec) {
    if (Test-Path $vbs) {
        Start-Process -FilePath 'wscript.exe' -ArgumentList "`"$vbs`"" -WindowStyle Hidden
        W "launched launch_butler.vbs (waiting up to ${waitSec}s)"
    } else {
        $py = FindPython
        Start-Process -FilePath $py -ArgumentList "`"$root\server\main.py`"" `
            -WorkingDirectory "$root\server" -WindowStyle Hidden `
            -RedirectStandardOutput $applog -RedirectStandardError "$root\butler.err.log"
        W "launched $py server\main.py (waiting up to ${waitSec}s)"
    }
    for ($i = 0; $i -lt $waitSec; $i++) {
        Start-Sleep -Seconds 1
        if (Listener) { return $true }
    }
    return $false
}

# Launch, and retry once with a longer leash. The first wscript launch can
# die silently when the port was still held at the moment it tried to bind.
$ok = Launch 30
if (-not $ok) {
    W 'first launch did not come up; retrying once'
    Start-Sleep -Seconds 2
    $ok = Launch 120
}

if ($ok) {
    $c = Listener
    W "listening again on $($c.LocalAddress):$port  pid=$($c.OwningProcess)"
} else {
    W 'ERROR: butler did not come back after two attempts'
}
W '--- done ---'
