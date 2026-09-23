[CmdletBinding()]
param(
    [Parameter(Position = 0)]
    [ValidatePattern('^(?:\d{1,3}\.){2}\d{1,3}$')]
    [string[]] $Subnet,

    [ValidateRange(1, 65535)]
    [int] $Port = 8080,

    [ValidateRange(100, 10000)]
    [int] $TimeoutMs = 500,

    [ValidateRange(1, 256)]
    [int] $ThrottleLimit = 64
)

$ErrorActionPreference = 'Stop'

function Get-ActiveIpv4Subnet {
    Get-NetIPAddress -AddressFamily IPv4 -AddressState Preferred |
        Where-Object {
            $_.IPAddress -notlike '127.*' -and
            $_.IPAddress -notlike '169.254.*' -and
            $_.PrefixLength -le 24
        } |
        ForEach-Object {
            $parts = $_.IPAddress.Split('.')
            [pscustomobject]@{
                Subnet = '{0}.{1}.{2}' -f $parts[0], $parts[1], $parts[2]
                LocalAddress = $_.IPAddress
                InterfaceAlias = $_.InterfaceAlias
            }
        } |
        Sort-Object Subnet -Unique
}

if ($Subnet) {
    $targets = $Subnet | ForEach-Object {
        $octets = $_.Split('.') | ForEach-Object { [int] $_ }
        if ($octets.Where({ $_ -lt 0 -or $_ -gt 255 }).Count -gt 0) {
            throw "无效网段：$_"
        }
        [pscustomobject]@{
            Subnet = $_
            LocalAddress = '(手动指定)'
            InterfaceAlias = '(手动指定)'
        }
    }
} else {
    $targets = @(Get-ActiveIpv4Subnet)
}

if ($targets.Count -eq 0) {
    throw '没有找到可扫描的活动 IPv4 网卡。请连接眼镜 Wi-Fi，或用 -Subnet 192.168.x 指定网段。'
}

Write-Host '将扫描以下 /24 网段：' -ForegroundColor Cyan
$targets | Format-Table Subnet, LocalAddress, InterfaceAlias -AutoSize

$addresses = foreach ($target in $targets) {
    1..254 | ForEach-Object { '{0}.{1}' -f $target.Subnet, $_ }
}
$addresses = @($addresses | Sort-Object -Unique)

Write-Host ("正在扫描 {0} 个地址的 TCP {1}，并验证 /v1/filelists ..." -f $addresses.Count, $Port) -ForegroundColor Cyan

$results = $addresses | ForEach-Object -Parallel {
    $ip = $_
    $portNumber = $using:Port
    $timeout = $using:TimeoutMs
    $tcp = [System.Net.Sockets.TcpClient]::new()

    try {
        $connectTask = $tcp.ConnectAsync($ip, $portNumber)
        if (-not $connectTask.Wait($timeout) -or -not $tcp.Connected) {
            return
        }
    } catch {
        return
    } finally {
        $tcp.Dispose()
    }

    $url = 'http://{0}:{1}/v1/filelists' -f $ip, $portNumber
    $handler = [System.Net.Http.HttpClientHandler]::new()
    $client = [System.Net.Http.HttpClient]::new($handler)
    $client.Timeout = [TimeSpan]::FromMilliseconds([Math]::Max(1000, $timeout * 4))

    try {
        $response = $client.GetAsync($url).GetAwaiter().GetResult()
        $body = $response.Content.ReadAsStringAsync().GetAwaiter().GetResult()
        $preview = if ($body.Length -gt 200) { $body.Substring(0, 200) } else { $body }

        [pscustomobject]@{
            IP = $ip
            Port = $portNumber
            StatusCode = [int] $response.StatusCode
            IsGlassesCandidate = $response.IsSuccessStatusCode -and $body.TrimStart().StartsWith('[')
            Url = $url
            Preview = $preview.Replace("`r", ' ').Replace("`n", ' ')
        }
    } catch {
        [pscustomobject]@{
            IP = $ip
            Port = $portNumber
            StatusCode = $null
            IsGlassesCandidate = $false
            Url = $url
            Preview = "端口开放，但 HTTP 请求失败：$($_.Exception.Message)"
        }
    } finally {
        $client.Dispose()
        $handler.Dispose()
    }
} -ThrottleLimit $ThrottleLimit

$results = @($results | Sort-Object @{ Expression = 'IsGlassesCandidate'; Descending = $true }, IP)

if ($results.Count -eq 0) {
    Write-Warning '没有发现开放 8080 端口的设备。请确认手机 App 已让眼镜进入文件传输模式，并确认电脑连接了眼镜热点或同一个 P2P/局域网。'
    exit 1
}

Write-Host ''
Write-Host '扫描结果：' -ForegroundColor Cyan
$results | Format-Table IP, Port, StatusCode, IsGlassesCandidate, Url -AutoSize

$candidates = @($results | Where-Object IsGlassesCandidate)
if ($candidates.Count -gt 0) {
    Write-Host ''
    Write-Host '疑似小米眼镜文件服务：' -ForegroundColor Green
    $candidates | Format-List IP, Url, StatusCode, Preview
    exit 0
}

Write-Warning '发现了开放的 8080 端口，但 /v1/filelists 未返回预期的 JSON 数组。上面的设备仍可手工检查。'
exit 2
