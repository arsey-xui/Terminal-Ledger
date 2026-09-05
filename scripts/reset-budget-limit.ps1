<#
.SYNOPSIS
Reset a category's budget to "no limit set" by soft-deleting its budget rows.

.DESCRIPTION
Directly edits the desktop local H2 database (default %USERPROFILE%\accounting\accounting.mv.db).
It sets deleted = 1 (soft-delete tombstone) and refreshes last_modified to the current
millisecond timestamp, so the change propagates to other clients via LWW sync.

IMPORTANT: Close the desktop app first. The app opens the DB with DB_CLOSE_DELAY=-1,
so while it is running the H2 data file is locked and the script cannot write.

.PARAMETER DbFile
Full path to the H2 database file. Default %USERPROFILE%\accounting\accounting.mv.db.
You may pass the base name ("accounting") or the full name including .mv.db.

.PARAMETER H2Jar
Full path to h2-x.x.x.jar. If omitted, the latest one is auto-discovered under
%USERPROFILE%\.m2\repository\com\h2database\h2.

.PARAMETER CategoryClientId
The category client_id to reset. Default cat-food (Dining).
Examples: cat-shopping (Shopping), cat-traffic (Transport).

.PARAMETER Year
Optional: only process budgets of this year (e.g. 2026). Defaults to all years.

.PARAMETER Month
Optional: only process budgets of this month (1-12). Defaults to all months.

.PARAMETER WhatIf
Preview only: print the matching budget rows without modifying anything.

.EXAMPLE
.\scripts\reset-budget-limit.ps1
# Reset all Dining (cat-food) budgets to "no limit set"

.EXAMPLE
.\scripts\reset-budget-limit.ps1 -CategoryClientId cat-shopping -WhatIf
# Preview Shopping budgets without applying

.EXAMPLE
.\scripts\reset-budget-limit.ps1 -Year 2026 -Month 8
# Reset only the August 2026 Dining budget
#>

[CmdletBinding()]
param(
    [string]$DbFile,
    [string]$H2Jar,
    [string]$CategoryClientId = 'cat-food',
    [int]$Year,
    [int]$Month,
    [switch]$WhatIf
)

$ErrorActionPreference = 'Continue'

# ---------- 1. locate the H2 driver jar ----------
function Resolve-H2Jar {
    param([string]$Explicit)
    if ($Explicit) {
        if (Test-Path -LiteralPath $Explicit) { return (Resolve-Path -LiteralPath $Explicit).Path }
        throw "H2 jar not found: $Explicit"
    }
    $m2 = Join-Path $env:USERPROFILE '.m2\repository\com\h2database\h2'
    if (Test-Path $m2) {
        $jars = Get-ChildItem $m2 -Recurse -Filter 'h2-*.jar' -File | Sort-Object FullName -Descending
        if ($jars) { return $jars[0].FullName }
    }
    throw "No H2 jar found. Pass -H2Jar, or build the desktop module (mvn package) to fetch it."
}

# ---------- 2. locate the H2 data file / build the URL ----------
if (-not $DbFile) {
    $DbFile = Join-Path $env:USERPROFILE 'accounting\accounting.mv.db'
}

$resolved = $null
if (-not (Test-Path -LiteralPath $DbFile)) {
    $baseCandidate = "$DbFile.mv.db"
    if (Test-Path -LiteralPath $baseCandidate) {
        $resolved = (Resolve-Path -LiteralPath $baseCandidate).Path
    }
} else {
    $resolved = (Resolve-Path -LiteralPath $DbFile).Path
}

if (-not $resolved) {
    Write-Host "ERROR: database file not found: $DbFile" -ForegroundColor Red
    Write-Host "Run the desktop app at least once; data is created under %USERPROFILE%\accounting\." -ForegroundColor Yellow
    exit 1
}

$dbBase = $resolved
if ($dbBase.ToLower().EndsWith('.mv.db')) {
    $dbBase = $dbBase.Substring(0, $dbBase.Length - 6)
}
$url = "jdbc:h2:$dbBase"
$h2Jar = Resolve-H2Jar

Write-Host "H2 jar : $h2Jar"  -ForegroundColor DarkGray
Write-Host "DB     : $dbBase" -ForegroundColor DarkGray
Write-Host ""

# ---------- 3. build the query parts ----------
$where = "category_client_id='$CategoryClientId'"
if ($PSBoundParameters.ContainsKey('Year'))  { $where += " AND budget_year=$Year" }
if ($PSBoundParameters.ContainsKey('Month')) { $where += " AND budget_month=$Month" }
$cols = "client_id, category_client_id, budget_year, budget_month, amount_limit, last_modified, deleted"
$preSql = "SELECT $cols FROM budget WHERE $where;"

# ---------- 4. run helper ----------
function Invoke-H2Shell {
    param([string]$Sql)
    # Do NOT pass -user/-password: the app creates/reads/writes this DB via
    # DriverManager.getConnection(url) with no user/password. Passing "-user sa" fails
    # against the real app DB with "Wrong user name or password [28000-224]".
    $out = & java -cp "$h2Jar" org.h2.tools.Shell -url $url -sql $Sql 2>&1 | Out-String
    [pscustomobject]@{ Output = $out; ExitCode = $LASTEXITCODE }
}

# ---------- 5. preview before ----------
Write-Host "== BEFORE: matching budgets =="
$pre = Invoke-H2Shell $preSql
Write-Host $pre.Output
if ($pre.ExitCode -ne 0) {
    Write-Host ""
    Write-Host "Connect/query failed (exit=$($pre.ExitCode))." -ForegroundColor Red
    if ($pre.Output -match 'already in use|in use|locked|Database may be already in use') {
        Write-Host "The DB file is locked: the desktop app is probably still running. Close it fully and retry." -ForegroundColor Yellow
    }
    exit $pre.ExitCode
}

# ---------- 6. WhatIf: preview only ----------
if ($WhatIf) {
    Write-Host ""
    Write-Host "WHATIF: the rows above would be reset to 'no limit set' (deleted=1 + refreshed last_modified)." -ForegroundColor Cyan
    Write-Host "Nothing was modified. Run without -WhatIf to apply." -ForegroundColor Cyan
    exit 0
}

# ---------- 7. apply the update ----------
$now = [DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds()
$updSql = "UPDATE budget SET deleted=1, last_modified=$now, updated_at=CURRENT_TIMESTAMP WHERE $where;"
Write-Host "== UPDATE: $updSql ==" -ForegroundColor DarkGray
$upd = Invoke-H2Shell "$updSql`n$preSql"
Write-Host $upd.Output
if ($upd.ExitCode -ne 0) {
    Write-Host "Update failed (exit=$($upd.ExitCode))." -ForegroundColor Red
    exit $upd.ExitCode
}

Write-Host ""
Write-Host "Done: '$CategoryClientId' budgets are now reset to 'no limit set' (soft-delete tombstone)." -ForegroundColor Green
Write-Host "Tip: next time you open the app and run a sync, the tombstone is pushed to the server." -ForegroundColor DarkGray
