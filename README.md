# 安大信

**这个仓是 iOS 这条线**：把协议层和界面抽进 Kotlin 多平台共享模块（`shared/`），一套代码出 Android 和 iOS 两个包，iOS 的壳在 `iosApp/`、出包靠 GitHub Actions 的 macOS 机器。Android 的正式发版不在这里——还在 [andaxin 主仓](https://github.com/linjianwuovo/andaxin)那条线上；这里也带着 Android 那份源码，因为共享内核就是从它长出来的，两边各自往前走。

给本校学生自己写的校园应用：课表、消息、公告、运动、查寝、报修这些原本散在四五个官方入口里的东西，放在一处。

纯客户端。数据直连校方与第三方厂商的接口，没有我们自己的服务器；本机只存登录状态、外观设置、宿舍位置和自绘的跑步路线。

## 这不是官方产品

作者是一个在校学生，跟学校没有任何隶属关系，也不代表安徽信息工程学院、安小信或任何第三方厂商。这里用的都是官方 App 本来就在调的那些接口，登录用的是你自己的账号，屏幕上出现的都是你自己账号权限内的东西。

凭据只在你自己手机上：口令用系统 Keystore 里一把不可导出的密钥加密后存本机，不进日志、不进崩溃上报、也不进这个仓。成绩、课表、班内名单这类属于别人的信息只在本机显示，不做导出。学校那边改了流程或者关掉某个入口，对应那一屏就会取不到数，屏幕上会写清是哪一条 —— 这是这类客户端的常态，不是哪里坏了。

## 这个仓的来历

从零写。不继承轻小信（Relianttt/lightxin）及其任何派生仓的一行源码，约束和自查办法见 [docs/CLEAN_ROOM.md](docs/CLEAN_ROOM.md)。

界面上"每一步出自我这"是硬要求：任何一块协议字段，落进代码时旁边要写清证据来自哪次抓包。取不到证的不写，宁可那一屏少显示一行。

## 怎么编

```bash
export JAVA_HOME=/d/app/jdk-21
./gradlew :app:assembleDebug        # 调试包
./gradlew :app:assembleRelease      # 正式包：R8 + 资源压缩 + 你的签名
```

`release` 要签名：在仓根放一个 `keystore.properties`（已经在 .gitignore 里），四个键 `storeFile / storePassword / keyAlias / keyPassword`。没有它 release 出来是未签名包，别当正式包发。密钥库和口令永远不进仓。

## 结构

```
app/AppGraph.kt      手写依赖图，全应用唯一 new 业务对象的地方
core/                平台适配：定位、瓦片地图、坐标换算、存储、网络、实况通知
data/brief/          列表 / 详情 / 表单三种契约
data/local/          占位实现：让每一屏在没抓包之前也能点、能看、能量帧率
data/                各块的 Contract 接口
state/ feature/      ViewModel 与界面
shell/               底栏五格、玻璃栏、公共小块
docs/architecture/   现状模型与目标分层
docs/接入清单.md      每块要哪些字段、还缺哪次抓包
```

依赖选型是性能优先的：没有 Hilt（手写 AppGraph）、没有 Retrofit+Gson（OkHttp + 无反射解析）、13 个 DataStore 合成一份、地图是自己写的 Compose 瓦片层、图标自己画。release 开 R8。

## 现在能干什么

底栏五格和二十几屏界面都在机上跑起来了。路线规划是真能用的：点图加拐点、撤销、存本机、回列表选它跑。

多数屏的数据还是占位 —— 屏幕上会一直挂着一条"这是占位数据"的说明，接上一路就划掉一路。所有写操作（报修提交、请假提交、销假、挂失、付款、跑步上传）一律停在最后一步由人按，没接上报文的那一侧直接回明确失败，不假装成功。

## 许可

Apache-2.0，全文见 [LICENSE](LICENSE)。第三方组件的许可、机制参考的来源、以及和轻小信那条线（MIT）的关系，逐条列在 [NOTICE](NOTICE)。
