# Cirno ColorOS 17 系统更新禁用

独立可选 Magisk / KernelSU / APatch 模块，用于禁用 OPPO / OnePlus / realme ColorOS 17（兼容 ColorOS 12+）的系统更新客户端。

## 它做什么

通过 `pm disable-user`（失败时回退 `pm suspend`）禁用以下已知 OTA 包中**实际安装**的那些：

- `com.oplus.ota` / `com.oplus.otaui` / `com.oplus.sau` / `com.oplus.romupdate`
- `com.oplus.systemupdate` / `com.oplus.appupdate` / `com.oplus.otaupdate`
- `com.coloros.ota` / `com.coloros.otaui` / `com.coloros.sau` / `com.coloros.romupdate`
- `com.oppo.ota` / `com.oppo.otaui` / `com.oppo.sau` / `com.oppo.romupdate`

不会修改 `system` / `product` / `vendor` 分区，不会删除系统文件。卸载模块时会自动 `pm enable` 恢复。

## 安装

在 Magisk / KernelSU / APatch 里直接刷入 `Cirno-ColorOS17-OTA-Disabler-v1.4.1.zip`，重启即可。

- 安装时会立即尝试禁用；recovery 刷入时由 `service.sh` 开机后补一次。
- 如果系统之后又把某个包启用，`service.sh` 下次开机会重新禁用。

## 恢复

在模块管理器里卸载本模块并重启。`uninstall.sh` 会立即恢复；如果卸载时 PackageManager 不可用，安装时写入的 `/data/adb/service.d/cirno_coloros_ota_guard.sh` 会在下次开机后兜底恢复。

## 查看状态

模块的「操作」按钮会调用 `action.sh`，显示哪些包已被本模块禁用。也可以手动执行：

```sh
su -c 'sh /data/adb/modules/cirno_coloros17_ota_block/ota_block.sh status'
```

## 注意

- 只针对 OPPO 系 ROM；其他设备刷入不会生效，也不会改系统分区，可直接卸载。
- 禁用 OTA 客户端后，系统设置里的「系统更新」可能打不开或一直转圈，这是预期行为。
- 模块不接管 `ro.build` / `persist.sys` 等属性，不做「伪装版本号」。
