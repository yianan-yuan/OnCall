<#
 ================================================================================
  publish-demo-alerts.ps1 —— Alertmanager 演示告警推送脚本
 ================================================================================
 【用途】
   演示链路：Prometheus 规则触发 -> Alertmanager -> 飞书适配器 -> 飞书群通知。
   脚本直接调用 Alertmanager 的 v2 HTTP API 手工推送 5 条告警，省去把 CPU 跑满、
   把磁盘写满的麻烦。（想让告警同时触发 AI 自动诊断，去 alertmanager.yml 放开 route.routes）

   5 条告警的 alertname 与 aiops-docs/ 下的 5 份运维手册一一对应：
     HighCPUUsage       -> aiops-docs/cpu_high_usage.md        (payment-service)
     HighMemoryUsage    -> aiops-docs/memory_high_usage.md     (order-service)
     HighDiskUsage      -> aiops-docs/disk_high_usage.md       (mysql)
     ServiceUnavailable -> aiops-docs/service_unavailable.md   (gateway-service)
     SlowResponse       -> aiops-docs/slow_response.md         (user-service)

 【前提】
   Alertmanager 与飞书适配器已启动：docker compose up -d alertmanager feishu-adapter
   适配器里填了飞书机器人地址（docker-compose.yml 的 FEISHU_WEBHOOK_URL），
   没填也能跑，只是适配器只打印不发送

 【运行方式】（在项目根目录执行，PowerShell 7 或 Windows PowerShell 5.1 均可）
   .\docker\publish-demo-alerts.ps1                          # 默认推到 localhost:9093
   .\docker\publish-demo-alerts.ps1 -AlertmanagerUrl "http://192.168.1.10:9093/api/v2/alerts"
   .\docker\publish-demo-alerts.ps1 -DryRun                  # 只打印 JSON，不推送
 ================================================================================
#>

[CmdletBinding()]
param(
    # Alertmanager v2 告警接口地址（POST 与校验查询都用它）
    [string]$AlertmanagerUrl = "http://localhost:9093/api/v2/alerts",

    # 只打印将要发送的 JSON，不真正发送
    [switch]$DryRun,

    # HTTP 超时时间（秒）
    [int]$TimeoutSec = 15
)

# 出错就让脚本停下来，避免“发送失败了还打印成功”
$ErrorActionPreference = "Stop"

Write-Host ""
Write-Host "================ Alertmanager 演示告警推送 ================" -ForegroundColor Cyan
Write-Host "目标地址 : $AlertmanagerUrl"
Write-Host "模式     : $(if ($DryRun) { 'DryRun（只打印 JSON，不发送）' } else { '真实推送' })"

# ---------------------------------------------------------------------------
# 1. 生成本次运行的唯一标识
#    ★ 说明：Alertmanager 的去重/指纹是根据 labels 算出来的（同样的 labels 会被
#      当成同一条告警，只更新其状态，不会重新通知）。所以除了题目要求的
#      fingerprint 字段外，这里还给每条告警加了一个唯一的 demo_run 标签，
#      保证脚本每次运行都是“一批新告警”，可以反复演示。
# ---------------------------------------------------------------------------
$runId = "demo-{0}-{1}" -f (Get-Date).ToUniversalTime().ToString("yyyyMMddTHHmmss"), (Get-Random -Minimum 1000 -Maximum 9999)

# startsAt：当前 UTC 时间的 ISO8601 格式，例如 2026-05-17T10:10:10Z
$startsAt = (Get-Date).ToUniversalTime().ToString("yyyy-MM-ddTHH:mm:ss'Z'", [System.Globalization.CultureInfo]::InvariantCulture)
Write-Host "本次运行标识 : $runId"
Write-Host "startsAt     : $startsAt"

# ---------------------------------------------------------------------------
# 2. 构造 5 条告警
#    字段严格按 Alertmanager v2 API 要求：
#      labels（必须有 alertname）/ annotations / startsAt / fingerprint
#      endsAt 这里**故意省略**，Alertmanager 会自己补一个默认结束时间，
#      告警会保持 firing 状态（否则会被立刻判定为已恢复）。
# ---------------------------------------------------------------------------
$alerts = @()

# ---- 告警 1：CPU 使用率过高（对应 aiops-docs/cpu_high_usage.md）----
$alerts += [ordered]@{
    labels = [ordered]@{
        alertname = "HighCPUUsage"
        service   = "payment-service"
        severity  = "critical"
        instance  = "pod-payment-service-7d8f9c6b5-x2k4m"
        demo_run  = $runId
    }
    annotations = [ordered]@{
        summary     = "CPU 使用率过高：payment-service 当前 92%"
        description = "payment-service 实例 pod-payment-service-7d8f9c6b5-x2k4m 的 CPU 使用率已达 92%，持续 5 分钟超过 80% 阈值。可能导致请求超时、响应变慢甚至雪崩。请参考 aiops-docs/cpu_high_usage.md 排查：确认是否有死循环/无限递归、流量突增、定时任务重叠执行或慢 SQL 导致 CPU 飙升。"
    }
    startsAt    = $startsAt
    fingerprint = "$runId-highcpuusage"
}

# ---- 告警 2：内存使用率过高（对应 aiops-docs/memory_high_usage.md）----
$alerts += [ordered]@{
    labels = [ordered]@{
        alertname = "HighMemoryUsage"
        service   = "order-service"
        severity  = "critical"
        instance  = "pod-order-service-6c9b7f4d8-m3p7q"
        demo_run  = $runId
    }
    annotations = [ordered]@{
        summary     = "内存使用率过高：order-service 当前 91%"
        description = "order-service 实例 pod-order-service-6c9b7f4d8-m3p7q 的内存使用率已达 91%，持续 5 分钟超过 85% 阈值，存在频繁 Full GC 与 OOM 风险。请参考 aiops-docs/memory_high_usage.md 排查：查看 JVM 堆使用与 GC 日志，确认是否存在内存泄漏、缓存配置不当、大文件处理或 JVM 参数不合理。"
    }
    startsAt    = $startsAt
    fingerprint = "$runId-highmemoryusage"
}

# ---- 告警 3：磁盘使用率过高（对应 aiops-docs/disk_high_usage.md）----
$alerts += [ordered]@{
    labels = [ordered]@{
        alertname = "HighDiskUsage"
        service   = "mysql"
        severity  = "warning"
        instance  = "mysql-master-01:9100"
        demo_run  = $runId
    }
    annotations = [ordered]@{
        summary     = "磁盘使用率过高：mysql 根分区使用率 88%"
        description = "数据库节点 mysql-master-01 根分区 / 使用率已达 88%，持续 5 分钟超过 85% 阈值。磁盘写满会导致日志无法落盘、数据文件损坏、实例不可写。请参考 aiops-docs/disk_high_usage.md 排查：重点检查 binlog/慢查询日志/错误日志是否过大、临时文件与备份文件堆积、以及 Docker 镜像与容器占用。"
    }
    startsAt    = $startsAt
    fingerprint = "$runId-highdiskusage"
}

# ---- 告警 4：服务不可用（对应 aiops-docs/service_unavailable.md）----
$alerts += [ordered]@{
    labels = [ordered]@{
        alertname = "ServiceUnavailable"
        service   = "gateway-service"
        severity  = "critical"
        instance  = "pod-gateway-service-5f8d6c9b4-t9w2x"
        demo_run  = $runId
    }
    annotations = [ordered]@{
        summary     = "服务不可用：gateway-service 健康检查失败"
        description = "gateway-service 实例 pod-gateway-service-5f8d6c9b4-t9w2x 健康检查连续失败（up == 0），错误率超过 50%，业务入口已中断。请参考 aiops-docs/service_unavailable.md 排查：确认进程与端口是否存活、数据库/Redis/MQ 等依赖服务是否故障、配置是否被改错、是否资源耗尽或网络不通。"
    }
    startsAt    = $startsAt
    fingerprint = "$runId-serviceunavailable"
}

# ---- 告警 5：响应时间过长（对应 aiops-docs/slow_response.md）----
$alerts += [ordered]@{
    labels = [ordered]@{
        alertname = "SlowResponse"
        service   = "user-service"
        severity  = "warning"
        instance  = "pod-user-service-8b7e5d3c2-h4k6n"
        demo_run  = $runId
    }
    annotations = [ordered]@{
        summary     = "服务响应时间过长：user-service P99 已达 4.2s"
        description = "user-service 实例 pod-user-service-8b7e5d3c2-h4k6n 的 P99 响应时间已达 4.2 秒，持续 5 分钟超过 3 秒阈值，用户侧可感知卡顿、请求开始堆积。请参考 aiops-docs/slow_response.md 排查：优先怀疑数据库慢查询、外部 API 调用超时、代码性能问题、缓存失效/缓存穿透以及系统资源不足。"
    }
    startsAt    = $startsAt
    fingerprint = "$runId-slowresponse"
}

# ---------------------------------------------------------------------------
# 3. 转成 JSON 数组（Alertmanager v2 要求 body 是数组，每项一个告警对象）
# ---------------------------------------------------------------------------
$jsonBody = ConvertTo-Json -InputObject @($alerts) -Depth 10
$bodyBytes = [System.Text.Encoding]::UTF8.GetBytes($jsonBody)   # 关键：中文注释必须按 UTF-8 编码发送

# 自检：确认 JSON 结构符合 Alertmanager v2 API 要求（顶层数组 + 必需字段齐全）
$parsed = $jsonBody | ConvertFrom-Json
$required = @("labels", "annotations", "startsAt", "fingerprint")
$structureOk = ($parsed -is [array]) -and ($parsed.Count -eq 5)
foreach ($item in $parsed) {
    foreach ($field in $required) {
        if (-not $item.PSObject.Properties.Name.Contains($field)) { $structureOk = $false }
    }
    if (-not $item.labels.PSObject.Properties.Name.Contains("alertname")) { $structureOk = $false }
    # endsAt 按题目要求省略（由 Alertmanager 自行补默认值），所以这里反向校验它不应该存在
    if ($item.PSObject.Properties.Name.Contains("endsAt")) { $structureOk = $false }
}
Write-Host "结构自检     : $(if ($structureOk) { '通过（顶层数组 5 项，labels/annotations/startsAt/fingerprint 齐全）' } else { '不通过，请检查脚本' })"
Write-Host "告警条数     : $($parsed.Count)"
Write-Host ""

if ($DryRun) {
    Write-Host "---------- DryRun：以下是将要 POST 的 JSON ----------" -ForegroundColor Yellow
    Write-Host $jsonBody
    Write-Host "---------- DryRun 结束，未发送任何请求 ----------" -ForegroundColor Yellow
    return
}

# ---------------------------------------------------------------------------
# 4. POST 推送（Content-Type: application/json，body 是 JSON 数组）
# ---------------------------------------------------------------------------
$pushOk = $false
$pushError = ""
try {
    $resp = Invoke-RestMethod -Uri $AlertmanagerUrl -Method Post -ContentType "application/json; charset=utf-8" `
        -Body $bodyBytes -TimeoutSec $TimeoutSec
    $pushOk = $true
}
catch {
    $pushError = $_.Exception.Message
}

Write-Host "推送结果     : $(if ($pushOk) { 'HTTP 200 成功（Alertmanager 已接收）' } else { "失败 -> $pushError" })"
if (-not $pushOk) {
    Write-Host ""
    Write-Host "排查建议：" -ForegroundColor Yellow
    Write-Host "  1) Alertmanager 是否已启动：docker compose up -d alertmanager"
    Write-Host "  2) 端口是否正确：http://localhost:9093 能否打开"
    Write-Host "  3) 地址是否写错：-AlertmanagerUrl 参数默认 http://localhost:9093/api/v2/alerts"
    return
}

# ---------------------------------------------------------------------------
# 5. 回查：从 Alertmanager 拉一次当前活跃告警，逐条打印本次推送的结果
#    （POST 成功只代表请求被接收，回查才能确认 5 条告警真的进了 Alertmanager）
# ---------------------------------------------------------------------------
Start-Sleep -Seconds 1
$activeAlerts = @()
try {
    # ⚠️ 这里必须写成 ${AlertmanagerUrl}?查询串：PowerShell 允许变量名里带 "?"，
    #    如果写成 "$AlertmanagerUrl?active=..."，会被当成名为 "AlertmanagerUrl?active"
    #    的变量（未定义 -> 空串），拼出来的 URL 就没有主机名了。
    $queryUrl = "${AlertmanagerUrl}?active=true&silenced=false&inhibited=false&unprocessed=false"
    $rawAlerts = Invoke-RestMethod -Uri $queryUrl -Method Get -TimeoutSec $TimeoutSec
    # Windows PowerShell 5.1 里 @(Invoke-RestMethod ...) 会得到“装着数组的数组”，
    # 直接管道过滤会把整批告警当成一条，这里显式摊平一层，保证逐条遍历
    foreach ($item in $rawAlerts) { $activeAlerts += $item }
}
catch {
    Write-Host "回查失败（不影响推送结果）: $($_.Exception.Message)" -ForegroundColor Yellow
}

Write-Host ""
Write-Host "---------------- 每条告警的推送结果 ----------------" -ForegroundColor Cyan

# alertname -> 对应运维手册 的映射，方便看结果时直接知道去看哪份文档
$docMap = @{
    "HighCPUUsage"       = "cpu_high_usage.md"
    "HighMemoryUsage"    = "memory_high_usage.md"
    "HighDiskUsage"      = "disk_high_usage.md"
    "ServiceUnavailable" = "service_unavailable.md"
    "SlowResponse"       = "slow_response.md"
}

$resultRows = @()
foreach ($alert in $parsed) {
    $name = $alert.labels.alertname
    $svc = $alert.labels.service
    $sev = $alert.labels.severity
    # 在 Alertmanager 当前活跃告警里找本次运行推的这条（用唯一标签 demo_run 精确定位）
    $hit = $activeAlerts | Where-Object {
        $_.labels.alertname -eq $name -and $_.labels.demo_run -eq $runId
    } | Select-Object -First 1

    $resultRows += [pscustomobject]@{
        "告警名(alertname)" = $name
        "服务(service)"     = $svc
        "级别(severity)"    = $sev
        "Alertmanager状态"  = $(if ($hit) { "已接收 / " + $hit.status.state } else { "已推送（回查未取到，可能刚入库）" })
        "对应运维手册"      = "aiops-docs/" + $docMap[$name]
    }
}
$resultRows | Format-Table -AutoSize

$receivedCount = ($resultRows | Where-Object { $_.'Alertmanager状态' -like '已接收*' }).Count
Write-Host "本次共推送 5 条告警，Alertmanager 回查确认 $receivedCount 条。" -ForegroundColor Green
Write-Host "浏览器打开 http://localhost:9093 可查看详情；飞书群里应收到通知，" -ForegroundColor Green
Write-Host "转发详情看 docker compose logs -f feishu-adapter。" -ForegroundColor Green
Write-Host ""
