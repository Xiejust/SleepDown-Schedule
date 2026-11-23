package com.xiaomanjun.sleepdownschedule.core.identity

import com.xiaomanjun.sleepdownschedule.BuildConfig

enum class DistributionChannel {
    GITHUB,
    STORE
}

object AppDistribution {
    val channel: DistributionChannel = when (BuildConfig.DISTRIBUTION_CHANNEL) {
        "github" -> DistributionChannel.GITHUB
        "store" -> DistributionChannel.STORE
        else -> error("Unknown SleepDown distribution channel: ${BuildConfig.DISTRIBUTION_CHANNEL}")
    }

    /**
     * 应用内自更新已关闭。
     *
     * 原实现从上游 Gitee Release 拉 APK，而那些包由原作者签名，与本包使用的
     * `xingmanye` 签名不一致，下载后必然安装失败；上游一旦发布更高版本，
     * 还会弹出误导性的更新提示。现在统一改为打开本仓库的 GitHub Releases 页面。
     */
    val supportsSelfUpdate: Boolean = false
}
