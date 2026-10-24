package com.andaxin.data.capture

import kotlinx.datetime.toLocalDateTime
import com.andaxin.core.json.JSONArray
import com.andaxin.core.json.JSONObject

/*
 * FIF（AI 课堂平台）的业务接口层。
 *
 * 【取证】这一族的每一条都对着我们自己盘出来的抓包清点写 —— `lxtmp/契约_AI课堂抓包.md`：
 * 90 条去重条目里 48 条响应真有过非空数据，发请求的都是官方安小信的 WebView
 * （`x-requested-with: cn.edu.aiit.axx`）。每个函数后面标了它在清点表里的编号（B6、C11 那种）。
 * 编号旁边写"回空"的意思是：**请求形状可信、字段名不可信**（那次服务器回的是空数组 / `data:null`）。
 *
 * 三条最容易踩的，都写在清点表里：
 * ① 判成功的口径每族不一样（见每个函数注释），别统一；
 * ② 四种 id 不能混：`courseId` 32 位、`teachClassId` 19 位、短学号 8 位、成员 id 32 位 ——
 *    同一个键名 `studentId` 在小组那族要 32 位成员 id，在别的族要 8 位短学号；
 * ③ 数组的位置不统一：`data` 直接是数组（课件、直录播、小组）、`data.dataList`（课程、学期）、
 *    `data.groupList`（组员）、`data.pages`（课件逐页）。只钻一种就会静默拿到空，
 *    界面上看着像"这门课没内容"。
 *
 * 10-20 上午这一版**已经在 PC 上拿活 token 逐发现打过一遍**（`lxtmp/adx_probe_fif2.py`、`fif3.py`）。
 * 打通并回到真数据的：学期、`myClassroom`(15 门)、直录播(高数 76 条 / 心理 8 条)、
 * 签到统计、签到记录、`getOneCourse`、`getFirstPageOfCourse`。
 * 打通但服务端回空的：课件(`data:null`)、小组(`data:[]`) —— 回空是真的没内容，不是接口写错。
 * 下面每条注释里"实测"指的就是这一轮。
 */
class FifApi(private val fif: FifSession) {

    /**
     * 学期列表。〔B2〕POST 空表单，200×46 次，`data.dataList[]` 53 条，
     * 条目 `year(4) num(1) name id(32) startDate endDate selected`。
     * `selected` 那条才是"现在这个学期"，它的 `id` 就是直录播那发要的 `termId`。
     */
    suspend fun terms(): List<AiTerm> {
        val root = post("$STTP/studycenter/mobile/common/termList", emptyList()) ?: return emptyList()
        return objs(data(root).opt("dataList")).map { o ->
            AiTerm(
                id = o.optString("id"),
                year = o.optString("year"),
                num = o.optString("num"),
                name = o.optString("name"),
                selected = o.optString("selected") == "1" || o.optString("selected") == "true",
            )
        }
    }

    /**
     * 我的课程。〔B1〕POST 表单 `termYear`(4) + `term`(1)，200×24 次，`data.dataList[]` 15 条、
     * 27 个键，样例 `courseName=思想道德与法治`。
     * 这发有 A 级实样 —— 所以课程列表不必再拿课表的 `jxbh` 去猜 FIF 的班号了。
     */
    suspend fun courses(termYear: String, term: String): List<AiCourse> {
        val root = post(
            "$STTP/studycenter/mobile/student/myClassroom",
            listOf("termYear" to termYear, "term" to term),
        ) ?: return emptyList()
        return objs(data(root).opt("dataList")).map { o ->
            AiCourse(
                id = o.optString("id"),
                name = o.optString("courseName").ifBlank { o.optString("name") },
                teacher = o.optString("teacherName"),
                courseId = o.optString("courseId"),
                // 19 位那个班号，课程条目里给的是 classId；mirrorTeachClassId 是另一个，别混
                teachClassId = o.optString("classId"),
                courseRecordId = o.optString("courseRecordId"),
                cover = o.optString("cover"),
            )
        }
    }

    /**
     * 课件列表。〔C11〕GET，200×20 次（其中只有 4 次回过数据），**`data` 直接是数组**，
     * 条目 17 键：`id(6) resourceId(32) coursewareId(24) resourceName resourceUrl fileType createTime
     * lockState isExamine chapterId isCollect …`。
     */
    suspend fun courseware(
        courseId: String,
        teachClassId: String,
        memberUserId: String,
        /**
         * 「其他资源」**不是另一条接口** —— 10-20 现打定死：同一条
         * `queryCoursewareResourceList`，只有这个值不同，**教学课件=1、其他资源=2**；
         * 填 `""/0/3/4/5/6` 一律 `data:null`，只有 1 和 2 是有定义的档。
         */
        resourceType: String = "1",
    ): List<AiItem> {
        val root = get(
            "$STTP/coursecenter-interaction/courseware/queryCoursewareResourceList" +
                "?courseId=${enc(courseId)}&teachClassId=${enc(teachClassId)}" +
                "&resourceType=${enc(resourceType)}&userId=${enc(memberUserId)}",
        ) ?: return emptyList()
        return objs(root.opt("data")).mapIndexed { i, o ->
            AiItem(
                id = o.optString("id").ifBlank { o.optString("resourceId") },
                title = o.optString("resourceName").ifBlank { "第 ${i + 1} 份课件" },
                sub = listOf(o.optString("fileType"), o.optString("createTime"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                extra = mapOf(
                    "resourceId" to firstOf(o, "resourceId", "id"),
                    // 官方那一发（C12）的入参写的是 **`courseWareId`（大写 W）**，
                    // 列表里这个键两种拼法都见过 —— 只读小写那个，取出来是空串，
                    // 换页图那一条就会静默回空（10-21 10:46 就是这么"没拿到页面图"的）。
                    "coursewareId" to firstOf(o, "courseWareId", "coursewareId"),
                    "url" to o.optString("resourceUrl"),
                ),
            )
        }
    }

    /**
     * 课程成员。〔现打 10-20 16:3x，真数据：74 名学生 + 1 名教师〕
     * `GET course/getCourseMemberList?teachClassId=<19>&type=0|1`，两个参数都**必带**：
     *  - 缺 `type` → `{"data":"","code":"1000","message":"服务端异常，请联系管理员"}`；
     *  - 缺 `teachClassId` → `status:"error"` + `message:"查询失败，不能访问"`；
     *  - **把键名写成 `courseId=<19>`（H5 调用点就是这么写的）→ HTTP 200 + status success + `data:{}`**，
     *    静默空，最阴的一种。键名以实测为准，别照 H5。
     *
     * 学生与教师**两套条目形状不一样**，这是本块最容易写错的地方：
     * 学生有 `studentId`(8 位数值)/`jobNo`(10 位学号)/`phone`/`email`，**没有 `userId`**；
     * 教师多了 `userId`(32 位成员 id)、**没有 `studentId`/`phone`**，且 `id` 位数不稳（3 位和 6 位都见过）。
     * `phone`/`email` 服务端返回了但全空串，界面别指望有联系方式。
     */
    suspend fun members(teachClassId: String, student: Boolean): List<AiItem> {
        val root = get(
            "$STTP/coursecenter-interaction/course/getCourseMemberList" +
                "?teachClassId=${enc(teachClassId)}&type=${if (student) "0" else "1"}",
        ) ?: return emptyList()
        val arr = root.optJSONObject("data")?.optJSONArray("dataList") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val name = o.optString("chineseName").ifBlank { o.optString("nickname") }
                .ifBlank { o.optString("jobNo") }
            AiItem(
                id = o.optString("jobNo").ifBlank { o.optString("id") },
                title = name,
                // joinTime 实测长度不统一（学生 19、教师 22），还夹过换行和制表符，压一下再显示
                sub = o.optString("jobNo"),
                right = if (student) "" else "教师",
                extra = mapOf("userId" to o.optString("userId")),
            )
        }
    }

    /**
     * 班级码两跳，回的是 **PNG 的 data-URI**（实测 `data` 是 1494 字符的串，不是对象）。
     * `GET course/getJoinCourseQRcodeTokenForMobileV3?scene_str=<19 位班号>&userName=<14 位登录名>`
     * → `GET qrcodeV2/getQrcodeByToken?token=<40 位>&size=500`。
     *
     * 两条实测出来的注意：① `userName` 要的是 `aiit`+学号那个 14 位登录名，
     * 只给 `scene_str` 不给 `userName` 会回「服务端异常，请联系管理员」；
     * ② `scene_str` 服务端**不校验**（填空串照样给 40 位 token），所以别把这个码当"绑定这门课"的依据；
     * ③ 这是**加入课堂/邀请**用的码，不是签到码 —— 签到扫码那一族（`checkQrcodeHandler` 等）
     *    至今零报文零证据，别混。
     */
    suspend fun classCode(teachClassId: String, userName: String): String? {
        val first = get(
            "$STTP/coursecenter-interaction/course/getJoinCourseQRcodeTokenForMobileV3" +
                "?scene_str=${enc(teachClassId)}&userName=${enc(userName)}",
        ) ?: return null
        val token = first.optString("data")
        if (token.length < 20) return null
        val second = get(
            "$STTP/coursecenter-interaction/qrcodeV2/getQrcodeByToken?token=${enc(token)}&size=500",
        ) ?: return null
        return second.optString("data").takeIf { it.startsWith("data:image") }
    }

    /**
     * 课件逐页图。〔C12〕GET 六参，实测那一次 `data.pages[]` **75 页**，每页 `imgUrl`
     * 是一张已渲染的 minio PNG（约 400 字符直链），另有 `page:int`、`flagType`。
     * `resourceId`(32) 与 `courseWareId`(6) 是两个不同的值，缺一个换不到图。
     */
    /**
     * 界面那一侧只用传这三个：schoolId 是 FIF 会话里的常量，别让界面去记它。
     * 没连上 FIF（ids 为空）时返回空表，调用方显示"还没连上"就行。
     */
    suspend fun coursewarePagesFor(
        resourceId: String,
        coursewareId: String,
        teachClassId: String,
    ): List<AiItem> {
        val schoolId = fif.ids()?.schoolId.orEmpty()
        if (schoolId.isBlank()) return emptyList()
        return coursewarePages(resourceId, coursewareId, schoolId, teachClassId)
    }

    suspend fun coursewarePages(
        resourceId: String,
        courseWareId: String,
        schoolId: String,
        teachClassId: String,
    ): List<AiItem> {
        val root = get(
            "$STTP/coursecenter-interaction/afterClassCoursewarePaper/viewCoursewareTeacher" +
                "?resourceId=${enc(resourceId)}&schoolId=${enc(schoolId)}&type=3" +
                "&courseWareId=${enc(courseWareId)}&openCourse=1&teachClassId=${enc(teachClassId)}",
        ) ?: return emptyList()
        return objs(data(root).opt("pages")).mapIndexed { i, o ->
            AiItem(
                id = o.optString("page").ifBlank { (i + 1).toString() },
                title = "第 ${i + 1} 页",
                sub = o.optString("flagType"),
                extra = mapOf("imgUrl" to o.optString("imgUrl")),
            )
        }
    }

    /**
     * 直录播列表。〔B6〕**是 POST 表单八键**（我上一版按 GET 写，错了），200×53 次，
     * `data` 直接是数组（见过 76 / 38 / 20 条三种长度），条目 30 键：
     * `liveRecordName teacherName roomName currentDate currentWeek classType section timeRange
     * isNowPlay isOpen isAction liveStatus livePath videoPath resourceId roomId videoClassMap[] …`。
     * 机位在 `videoClassMap[]` 里（`videoName` 就是"教师机位"这种）。
     * 样例 `liveRecordName=思想道德与法治_录播课_2026-09-22 16:45-17:30`。
     */
    suspend fun liveRecords(
        courseId: String,
        teachClassId: String,
        termId: String,
        /**
         * 周次筛选。抓包里官方那几发这一栏**一直是空串**（= 不按周次筛），
         * 所以传空是照实样发；传数字是我们自己加的用法，值域来自 `getWeekList` 的 `weekList`（实测 1..22）。
         * 服务端认不认这个筛选，**装机时才知道** —— 要是选了某一周还回全量，
         * 那这排 chip 就该撤掉，别让它看起来在筛。
         */
        weekNum: String = "",
    ): List<AiItem> {
        val root = post(
            "$STTP/studycenter/mobile/course/getTeachHourLiveAndRecordList",
            listOf(
                "roomType" to "0",
                "liveStatus" to "0",
                "termId" to termId,
                "weekNum" to weekNum,
                "dayNum" to "",
                "timeRange" to "",
                "courseId" to courseId,
                "teachClassId" to teachClassId,
            ),
        ) ?: return emptyList()
        return objs(root.opt("data")).map { o ->
            val cams = objs(o.opt("videoClassMap")).mapNotNull { c ->
                c.optString("videoName").takeIf { it.isNotBlank() }
            }
            AiItem(
                id = o.optString("resourceId").ifBlank { o.optString("id") },
                title = o.optString("liveRecordName").ifBlank {
                    o.optString("courseName").ifBlank { "录播" }
                },
                sub = listOf(o.optString("currentDate"), o.optString("roomName"), o.optString("teacherName"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                right = when {
                    o.optString("isNowPlay") == "1" -> "正在直播"
                    o.optString("isOpen") == "1" -> "可看"
                    else -> "未开放"
                },
                extra = mapOf(
                    "resourceId" to o.optString("resourceId"),
                    "roomId" to o.optString("roomId"),
                    "livePath" to o.optString("livePath"),
                    "机位" to cams.joinToString("/"),
                ),
            )
        }
    }

    /**
     * 录播播放地址。〔D1〕域名是 `resc.fifedu.com`，**不带 `visit-type`**，
     * `authorization` 要**裸 JWT**（带 `Basic ` 前缀它不认，回 200 + `data:null`，
     * 看着像"这节没录像"）。200×8 次，`data.videoList[]` 每条一个机位：
     * `videoCode`(1=教师机位) `videoName` `videoPath`(522 字符 minio 直链，当场签名、每次都变，
     * 所以不能缓存) `videoSize`（**字符串不是数字**）。
     */
    suspend fun videoSources(resourceId: String): List<AiItem> {
        val jwt = fif.bareJwt() ?: return emptyList()
        val root = runCatching {
            JSONObject(
                fif.getResc(
                    "$RESC/resource-center/app/api/videoClassInfo?isApp=1&resourceId=${enc(resourceId)}",
                    jwt,
                ),
            )
        }.getOrNull() ?: return emptyList()
        return objs(data(root).opt("videoList")).map { o ->
            AiItem(
                id = o.optString("videoCode"),
                title = o.optString("videoName").ifBlank {
                    if (o.optString("videoCode") == "2") "HDMI 机位" else "教师机位"
                },
                sub = sizeOf(o.optString("videoSize").toLongOrNull() ?: 0L),
                extra = mapOf("path" to o.optString("videoPath")),
            )
        }
    }

    /** 小组列表。〔B18〕GET 一个参数，200×7 次，`data` 直接是数组，条目 16 键。 */
    suspend fun groups(teachClassId: String): List<AiItem> {
        val root = get("$STTP/studycenter/mobile/groupTeaching/groupList?teachClassId=${enc(teachClassId)}")
            ?: return emptyList()
        return objs(root.opt("data")).map { o ->
            AiItem(
                id = o.optString("id"),
                title = o.optString("gName").ifBlank { "小组" },
                sub = "${o.optInt("gCount")}/${o.optInt("gCountLimit")} 人",
                right = if (o.optString("gStatus") == "1") "进行中" else o.optString("gStatus"),
            )
        }
    }

    /**
     * 我在哪个组。〔B19〕200×2 次：成员挂 `data.groupList[]`（实测 7 人，
     * `stuName stuNum isLeader className majorName departmentName photoUrl id`），
     * 我的组挂 `data.microGroupInfo{}`（`gId xName="第6组" xNum xCount xCode:null`），
     * 另有标量 `isBind:int=1`。**这发的 `studentId` 要 32 位成员 id。**
     */
    suspend fun myGroup(
        teachClassId: String,
        memberUserId: String,
        groupId: String,
    ): Pair<AiItem?, List<AiItem>> {
        val root = get(
            "$STTP/studycenter/mobile/groupTeaching/getStudentGroupInfo" +
                "?teachClassId=${enc(teachClassId)}&studentId=${enc(memberUserId)}&groupId=${enc(groupId)}",
        ) ?: return null to emptyList()
        val d = data(root)
        val members = objs(d.opt("groupList")).map { o ->
            AiItem(
                id = o.optString("id"),
                title = o.optString("stuName").ifBlank { o.optString("stuNum") },
                sub = listOf(o.optString("stuNum"), o.optString("majorName"))
                    .filter { it.isNotBlank() }.joinToString(" · "),
                right = if (o.optString("isLeader") == "1") "组长" else "",
            )
        }
        val mine = d.optJSONObject("microGroupInfo")
        val group = mine?.let {
            AiItem(
                id = firstOf(it, "gId", "groupId"),
                title = it.optString("xName").ifBlank {
                    d.optString("groupScreenName").ifBlank { "我的小组" }
                },
                sub = "${it.optInt("xCount")} 人",
            )
        }
        return group to members
    }

    /**
     * 小组讨论列表。〔B20〕**请求形状有实样、响应回空**（那次 `studentDis` 是空数组），
     * 所以条目字段名一个都没验到，这里只按候选键试。真拿到空数组时界面说的是"没有讨论"，
     * 不说"接口坏了" —— 这两种在证据上是两回事。
     * 讨论的详情/回复/点赞〔清点表结论〕**一条报文都没有**，所以那几发根本没写，
     * 别哪天看见界面少一栏就去编一个接口名。
     */
    suspend fun discussions(microGroupId: String, memberUserId: String): List<AiItem> {
        val root = get(
            "$STTP/studycenter/mobile/groupTeaching/findStudentSelfGroupDiscuss" +
                "?microGroupId=${enc(microGroupId)}&studentId=${enc(memberUserId)}",
        ) ?: return emptyList()
        val d = data(root)
        val a = arrOrNull(d.opt("studentDis")) ?: arrOrNull(d.opt("discussList")) ?: JSONArray()
        return objsOf(a).map { o ->
            AiItem(
                id = firstOf(o, "id", "discussId"),
                title = firstOf(o, "title", "discussTitle", "name").ifBlank { "讨论" },
                sub = firstOf(o, "createTime", "gmtCreate"),
                right = firstOf(o, "answerCount", "count"),
            )
        }
    }

    /**
     * 签到统计 + 签到记录。〔C20 + C21〕两发 GET，参数一模一样：
     * `id=<8 位短学号>` + `courseId=<19 位班号>`。
     *
     * **这里的 `courseId` 装的是 19 位那个班号，不是 32 位 courseId** —— 清点表 C19/C20/C21 三发
     * 都记成 `courseId:str(19)`。签到这一族跟别处反着来，照别处的习惯填 32 位就是一直回空。
     *
     * 【取证】两条都有实样：C20 抓到 5 次、回 `data{}` 五个整数
     * `total=7 chidao=0 chuqin=7 kuangke=0 qingjia=0`；C21 抓到 5 次、
     * 数组挂 `data.dataList[] len=7`，条目 4 键 `signName="2026.10.08" signTime state:int=0 signId:int`。
     *
     * 10-20 现打到的真值：高等数学 `total=10 chuqin=9 qingjia=1 chidao=0 kuangke=0`，
     * 记录 10 条、`state` 只出现 0 和 2；思想道德与法治 3 条全 0；心理健康 1 条 0。
     * 拿这两发对账能推出 0=出勤、2=请假，但那是**一门课的一次巧合**，服务端没给过枚举表，
     * 所以 `state` 这一栏**不做显示**，只把五个数摆出来 —— 万一哪天 1 是迟到，
     * 我按猜的枚举标出来的就是错的话。
     *
     * 另一件值得记的：`signName` 和 `signTime` 可以差很远（实样 `signName="2026.08.27"` 而
     * `signTime="2026.10.13 15:45:52"`）。所以那个时间不能叫"签到时间"，它是这条记录被写下的时间，
     * 补签/改状态都会挪它。界面上副标题只放原样字符串，不写"签于"。
     */
    suspend fun attendance(shortStudentId: String, teachClassId: String): AiAttendance {
        val q = "?id=${enc(shortStudentId)}&courseId=${enc(teachClassId)}"
        val counts = get("$STTP/coursecenter-interaction/sign/getSignStateNumber$q")?.optJSONObject("data")
        val num: (String) -> Int = { k -> counts?.optInt(k, 0) ?: 0 }
        val list = get("$STTP/coursecenter-interaction/sign/getStuSignList$q")
        val records = objs(data(list ?: JSONObject()).opt("dataList")).mapIndexed { i, o ->
            AiItem(
                id = o.optString("signId").ifBlank { i.toString() },
                title = o.optString("signName").ifBlank { "第 ${i + 1} 次签到" },
                // 服务端给的时间串里夹着 \n 和 \t（"2026.10.08\n\t\t08:16:14"），原样显示会撑破一行
                sub = o.optString("signTime").replace(Regex("\\s+"), " ").trim(),
            )
        }
        return AiAttendance(
            total = num("total"),
            present = num("chuqin"),
            late = num("chidao"),
            absent = num("kuangke"),
            leave = num("qingjia"),
            records = records,
            evidenced = counts != null,
        )
    }

    /**
     * 上课记录（这门课开过哪几节、每节几点到几点）。〔C3〕
     *
     * 【取证 · ② 10-20 现打到的】POST 五键，**服务端那个键名就是拼错的 `studnetId`**（不是 studentId），
     * 里面装的还是 32 位成员 id；`courseId` 这一发要的是 19 位班号（跟 C11 那个 32 位的不是一回事）。
     * 回的是 `data[]` **按天分组**，实测高等数学 5 天、心理健康 1 天、没开过课的 0 天：
     * `{dayTime:"10月19日 星期一", groupId:null, microGroupId:null, courseRecords:[…]}`，
     * 条目里真用到的键：`id(32) coursewareId:null coursewareName startTime endTime finishStatus:int=1`，
     * 其余 `weikeIds/paperIds/quickAnswerIds/voteIds/discussIds/teacherName/yearMonth/fromType`
     * **实测全是 null** —— 所以这一栏只报"哪天、几点到几点、完没完"，不装作知道那节课有哪些互动。
     *
     * 时间是 `2026-10-19T00:23:40.000+00:00` 这种 **UTC 带偏移**的串，直接截字符串会差八小时，
     * 必须按 Instant 解析再转本机时区。
     */
    suspend fun courseRecords(teachClassId: String, termId: String, memberUserId: String): List<AiItem> {
        val root = post(
            "$STTP/coursecenter-interaction/courseRecord/getFirstPageOfCourse",
            listOf(
                "teachClassId" to teachClassId,
                "courseId" to teachClassId,
                "pageNum" to "1",
                "termId" to termId,
                "studnetId" to memberUserId,
            ),
        ) ?: return emptyList()
        return objs(root.opt("data")).flatMap { day ->
            val label = day.optString("dayTime")
            objs(day.opt("courseRecords")).map { r ->
                AiItem(
                    id = r.optString("id"),
                    title = label.ifBlank { "一节课" },
                    // 正在上的那一节服务端还没写 endTime，`optString` 会给出字面 "null"，
                    // 装机读屏就印成「10-20 15:39 – null」。空和 "null" 都算没下课。
                    sub = utc(r.optString("startTime")) + " – " + (
                        r.optString("endTime")
                            .takeIf { it.isNotBlank() && it != "null" }
                            ?.let { utc(it) } ?: "还没下课"
                    ),
                    right = when (r.optInt("finishStatus", -1)) {
                        1 -> "已完成"
                        else -> ""
                    },
                )
            }
        }
    }

    /**
     * 未读数角标。〔B4〕POST 空表单，200×36 次，信封是
     * `{data:int, status:int=1, message:"ok", success:true}` ——
     * **这一发 `status` 是数字 1 才算过**，跟别处那个 `"success"` 字符串不一样。
     * 失败一律当 0，不报错：角标不值得把整屏拖成错误态。
     */
    suspend fun unread(): Int {
        val root = runCatching {
            JSONObject(
                fif.postForm("$STTP/studycenter/mobile/ZjzxMessage/getUnReadCount", emptyList(), fif.authHeader()),
            )
        }.getOrNull() ?: return 0
        val ok = root.optBoolean("success", false) || root.optInt("status", -1) == 1
        if (!ok) return 0
        return when (val d = root.opt("data")) {
            is Number -> d.toInt()
            is JSONObject -> d.optInt("count")
            else -> 0
        }
    }

    /**
     * 学期周次表。〔10-20 从抓包真值对出来：现存文件里命中 61 次〕
     * `GET studycenter/mobile/course/liveAndRecord/getWeekList?identity=2` →
     * `data{currentWeek:"8", weekList:[1,2,…]}` —— `weekList` 实测 22 个整数，就是这学期一共多少周；
     * `currentWeek` 是"现在第几周"。
     *
     * `identity` 这个参数官方给的是 **2**（学生侧）。1 是什么身份没实样，不试。
     * 这一发的用处只有给直录播那一栏当周次筛选的候选，别的都不动 ——
     * 校方"现在是第几周"另有那条 `selectZcList.do`，两处都报到 8，但那是巧合不是同源。
     */
    suspend fun weekList(): Pair<Int, List<Int>> {
        val root = get("$STTP/studycenter/mobile/course/liveAndRecord/getWeekList?identity=2")
            ?: return 0 to emptyList()
        val d = data(root)
        val cur = d.optString("currentWeek").toIntOrNull() ?: 0
        val arr = d.optJSONArray("weekList") ?: JSONArray()
        return cur to (0 until arr.length()).map { arr.optInt(it, 0) }
    }

    /**
     * 章节模式（这门课按章/节铺开的那棵树）。
     *
     * 【取证 · ③ 真 App 发出、我们抓到过响应真值】`GET studycenter/mobile/course/chapter/chapterMode?teachClassId=<19>`
     * 回 `data.dataList[]`，每条是一个"教材"：`{id:"-1", materialId:"-1", materialName:自建章节, courseId:<32>, sourceType:"1", chapterList[]}`；
     * `chapterList` 里是章，**下一层挂在 `children`**，键完全同名（18 个键两层一样）：
     * `chapterId(32) chapterName level(1|2) sort parentId plateId(7) relCourseId(6)
     * lockModeStatus unlock itemCnt learnedItemCnt isLearning isFinish visibilityType hasNewItem materialId publicTime`。
     * 真值里那一门课是「第一章」下面挂「第一节」，两层 `itemCnt` 都是 **0** ——
     * 这正好解释了为什么 `getSubsection*` 那五发全回空：这一节本来就没挂内容，不是接口坏了。
     *
     * 另一发 `chapter/newResourceChapter?teachClassId=` 命中 6 次、`data` 每次都是空串，
     * 一条字段样本都没有，所以**没写**，别哪天照着名字猜一个解析出来。
     */
    suspend fun chapters(teachClassId: String): List<AiChapter> {
        val root = get("$STTP/studycenter/mobile/course/chapter/chapterMode?teachClassId=${enc(teachClassId)}")
            ?: return emptyList()
        return objs(data(root).opt("dataList")).flatMap { material ->
            objs(material.opt("chapterList")).flatMap { walkChapter(it, 1) }
        }
    }

    /** 章在前、它的节紧跟在后面；递归只到 children，实测两层。 */
    private fun walkChapter(o: JSONObject, level: Int): List<AiChapter> = buildList {
        add(
            AiChapter(
                id = o.optString("chapterId"),
                title = o.optString("chapterName").ifBlank { if (level == 1) "一章" else "一节" },
                level = level,
                items = o.optInt("itemCnt"),
                learned = o.optInt("learnedItemCnt"),
                finished = o.optString("isFinish") == "1",
                locked = o.optString("unlock") == "0",
                hasNew = o.optString("hasNewItem") == "1",
                relCourseId = o.optString("relCourseId"),
            )
        )
        objs(o.opt("children")).forEach { addAll(walkChapter(it, level + 1)) }
    }

    /**
     * 某一节下面挂了哪些东西 —— 五发一起问。
     *
     * 【取证 · 只有一半】请求侧五发**都有实样**（参数一套不差，见下面每个调用），
     * 响应侧**全部是 `data:""`**（`lxtmp/_sweep_形状.md` 那 19 条里就有这五条）。
     * 所以：路径、参数名、参数怎么拼是硬的，**条目里的键名一个都没有依据**。
     * 这里按候选键轮一遍，界面上拿到的每一条都带着"字段名未证实"这句话，
     * 并且真一条都没拿到时说的是"这一节没挂内容"（跟 chapterMode 实测 `itemCnt=0` 一致），
     * 而不是"接口坏了"。
     */
    suspend fun subsectionItems(
        courseId: String,
        teachClassId: String,
        subsectionId: String,
        termId: String,
        memberUserId: String,
    ): List<AiItem> {
        val common = listOf("subsectionId" to subsectionId, "courseId" to courseId, "teachClass" to teachClassId)
        val roots = listOf(
            post("$STTP/studycenter/mobile/course/getSubsectionCourseWare", common + ("userId" to memberUserId)),
            // 这一发是 GET，而且多一个 appId=akt（抓包里就这么带的）
            get(
                "$STTP/studycenter/mobile/course/getSubsectionMicroCourseList" +
                    "?teachClass=${enc(teachClassId)}&courseId=${enc(courseId)}" +
                    "&subsectionId=${enc(subsectionId)}&appId=akt"
            ),
            post("$STTP/studycenter/mobile/course/getSubsectionPaper", common),
            post("$STTP/studycenter/mobile/course/getSubsectionTopicList", common),
            post("$STTP/studycenter/mobile/course/getSubsectionLiveAndRecordIds", common + ("termId" to termId)),
        )
        return roots.filterNotNull().flatMap { root ->
            // `data` 实测是空串；真给东西时不知道挂哪儿，所以数组、dataList、list 三种都试
            (arrOrNull(root.opt("data")) ?: arr(data(root).opt("dataList")).takeIf { it.length() > 0 }
                ?: arr(data(root).opt("list")))
                .let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } }
        }.mapIndexed { i, o ->
            AiItem(
                id = firstOf(o, "id", "resourceId", "coursewareId", "paperId", "topicId", "microCourseId"),
                title = firstOf(
                    o, "resourceName", "coursewareName", "paperName", "topicName",
                    "microCourseName", "name", "title", "liveRecordName",
                ).ifBlank { "第 ${i + 1} 项" },
                sub = firstOf(o, "createTime", "pubTime", "teacherName", "fileType"),
                right = "",
            )
        }
    }

    /**
     * 公开课：热门 / 最新 / 全部，三条口同一个条目形状（真值 15 键完全一致）。
     *
     * 【取证 · ③ 响应真值】`GET studycenter/mobile/openCourse/{hotCourseList,newCourseList,homePageInfoAllCourse}`，
     * 参数 `page`、`pageSize`（列表那两条要）、`schoolId=<19 位>`、`courseName=`（空串就是不过滤）。
     * 回 `data.dataList[]`，条目 15 键，实测用到的：
     * `courseId`（**注意：这是 4~5 位的短号**）、`courseUid`（32 位）、`courseName`、
     * `teacherName`、`cover`（iplat-resource 或 minio 的直链）、`signCount:int`（报名人数）、
     * `teachClassId`（19 位）、`type`（真值 7 / 9）、`typeName`/`teacherNames`/`isCollect`/`url` 实测都空。
     *
     * 那个 `courseUid` 才是 `adapt-app` 那三条口要的 `courseId` —— 交叉验证过：
     * 热门第一条 `courseUid=807802d8…`，而 `adapt-app/courseApi/courseDetail?courseId=807802d8…` 回的课名一模一样。
     * 拿短号 `courseId` 去问 adapt-app 是问不到的，这一条只有对真值才能发现。
     */
    suspend fun openCourses(
        kind: String,
        schoolId: String,
        page: Int = 1,
        pageSize: Int = 20,
        courseName: String = "",
    ): List<OpenCourse> {
        val path = when (kind) {
            "new" -> "newCourseList"
            "all" -> "homePageInfoAllCourse"
            else -> "hotCourseList"
        }
        val q = if (path == "homePageInfoAllCourse") {
            "schoolId=${enc(schoolId)}&courseName=${enc(courseName)}"
        } else {
            "page=$page&pageSize=$pageSize&schoolId=${enc(schoolId)}&courseName=${enc(courseName)}"
        }
        val root = get("$STTP/studycenter/mobile/openCourse/$path?$q") ?: return emptyList()
        return objs(data(root).opt("dataList")).map { openCourseOf(it) }
    }

    /**
     * 公开课首页那几个栏目（实测 2 栏：栏名 + 栏里带的课）。
     * `GET openCourse/customModuleList?schoolId=&courseName=` → `data.dataList[]`，
     * 每条 `{moduleId, moduleName, sort, templateId, columnId, code:"mc_col_2", type, updateCycle,
     * updateMethod, urlPrefix:null, isCommon, courseList[]}`，`courseList` 的条目形状跟上面完全一样。
     */
    suspend fun openModules(schoolId: String): List<Pair<String, List<OpenCourse>>> {
        val root = get(
            "$STTP/studycenter/mobile/openCourse/customModuleList" +
                "?schoolId=${enc(schoolId)}&courseName="
        ) ?: return emptyList()
        return objs(data(root).opt("dataList")).map { m ->
            m.optString("moduleName") to objs(m.opt("courseList")).map { openCourseOf(it) }
        }.filter { it.first.isNotBlank() || it.second.isNotEmpty() }
    }

    private fun openCourseOf(o: JSONObject) = OpenCourse(
        id = o.optString("courseUid").ifBlank { o.optString("courseId") },
        shortId = o.optString("courseId"),
        uid = o.optString("courseUid"),
        name = o.optString("courseName"),
        teacher = o.optString("teacherName"),
        cover = o.optString("cover"),
        signCount = o.optInt("signCount"),
        teachClassId = o.optString("teachClassId"),
        type = o.optString("type"),
        schoolName = o.optString("schoolName"),
    )

    /**
     * 公开课详情（`adapt-app` 那一族）。
     *
     * 【取证 · ③ 响应真值】`GET adapt-app/courseApi/courseDetail?courseId=<32 位 courseUid>`，
     * 信封跟 studycenter 那族**不一样**：`status` 是**数字 1**、`message:"OK"`、还多一个根键 `jtzy:null`，
     * `code` 是 null（`passed()` 只看 status，所以照过）。
     * `data` 23 键，真用到的：`courseName courseCode(MTH101) subjectName(通识课) teacherId
     * aimindName aimindId graphStatus:int courseMastery:int status:int imageUrl/courseCover2`；
     * `courseDesc studentNumber validityDate token tripartiteUrl isReply personalStatus
     * encryptionSchoolId ifShowRobot` 实测**全是 null** —— 所以这一屏不给"课程简介"，它没有。
     */
    suspend fun adaptDetail(courseUid: String): AdaptCourse? {
        val root = adaptGet("/adapt-app/courseApi/courseDetail?courseId=${enc(courseUid)}") ?: return null
        val d = data(root)
        if (d.length() == 0) return null
        return AdaptCourse(
            id = d.optString("courseId"),
            name = d.optString("courseName"),
            code = d.optString("courseCode"),
            subject = d.optString("subjectName"),
            teacherId = d.optString("teacherId"),
            cover = d.optString("courseCover2").ifBlank { d.optString("imageUrl") },
            mastery = d.optInt("courseMastery"),
            graph = d.optString("graphStatus"),
        )
    }

    /**
     * 公开课的章节与学习进度。
     *
     * 【取证 · ③ 响应真值】`GET adapt-app/courseApi/newCourseChapterList?courseId=<32 位>` →
     * `data{targetNumber:int, studiedNumber:int, courseChapterDtoList[], quizDtoList:null}`，
     * 章的条目 10 键：`chapterId(32) chapterName type:null chapterType(6 位数字) activityCount:int
     * children[] targetNumber studiedNumber currentRate:null chapterDistinguish(1=章 2=节)`。
     * 实测那一门课 `targetNumber=215`、`studiedNumber=0`，7 章、每章下面 5 节左右，
     * 节上带的 `targetNumber`/`studiedNumber` 是这一节自己的数 —— 所以进度两层都能算。
     * `currentRate` 实测是 null，别拿它当百分比显示。
     */
    suspend fun adaptChapters(courseUid: String): Pair<AdaptProgress, List<AiChapter>> {
        val root = adaptGet("/adapt-app/courseApi/newCourseChapterList?courseId=${enc(courseUid)}")
            ?: return AdaptProgress(0, 0) to emptyList()
        val d = data(root)
        val progress = AdaptProgress(d.optInt("studiedNumber"), d.optInt("targetNumber"))
        val out = ArrayList<AiChapter>()
        fun walk(o: JSONObject, level: Int) {
            out += AiChapter(
                id = o.optString("chapterId"),
                // 章名实测长这样：「第1章 函数」「1.1 集合」，服务端已经带好前缀，我们不再加"第几节"
                title = o.optString("chapterName").ifBlank { if (level == 1) "一章" else "一节" },
                level = level,
                items = o.optInt("targetNumber"),
                learned = o.optInt("studiedNumber"),
                finished = level > 1 && o.optInt("targetNumber") > 0 &&
                    o.optInt("studiedNumber") >= o.optInt("targetNumber"),
                activityCount = o.optInt("activityCount"),
            )
            objs(o.opt("children")).forEach { walk(it, level + 1) }
        }
        objs(d.opt("courseChapterDtoList")).forEach { walk(it, 1) }
        return progress to out
    }

    /**
     * 公开课的测验列表。
     * 【取证 · ③】`GET adapt-app/courseApi/getQuizList?courseId=<32 位>` →
     * `data{studiedNumber, targetNumber, courseChapterDtoList:null, quizDtoList:[]}` ——
     * **实测那一发 `quizDtoList` 是空数组**，所以条目的键名一个都没验到。
     * 这一发照样接：拿到空就明说"校方这条给了，但这一门课没有测验"，
     * 拿到非空就走候选键，并明写"字段名未证实"。
     */
    suspend fun adaptQuizzes(courseUid: String): List<AiItem> {
        val root = adaptGet("/adapt-app/courseApi/getQuizList?courseId=${enc(courseUid)}") ?: return emptyList()
        val arr = data(root).optJSONArray("quizDtoList") ?: JSONArray()
        return (0 until arr.length()).mapNotNull { i -> arr.optJSONObject(i) }.mapIndexed { k, o ->
            AiItem(
                id = firstOf(o, "id", "quizId", "paperId"),
                title = firstOf(o, "name", "title", "quizName", "paperName").ifBlank { "测验 ${k + 1}" },
                sub = firstOf(o, "startTime", "endTime", "createTime"),
                right = "",
            )
        }
    }

    /**
     * `adapt-app` 那一族**单独一条 GET**：`Authorization` 要**裸 JWT**（不带 `Basic ` 前缀），
     * 还要带 `Referer: https://sttp.fifedu.com/adaptive/`。
     *
     * 【取证 · ③ 真 App 发出的请求头 + ② 我们现打撞出来的】
     * 10-20 晚装机点公开课详情，照主站习惯发 `Basic <jwt>`，服务器回的是
     * `{"status":401,"message":"The string '…' doesn't have a valid JSON format"}` ——
     * 它把 "Basic " 后面那段当 base64 解、解出来不是 JSON，所以报这句（`lxtmp/_adapt_probe.txt`）。
     * 把官方那几发的请求头从抓包里逐条读出来才对上：`Authorization: eyJ0…`（裸 JWT）
     * + `Referer: …/adaptive/`，`Visit-Type` 这一族**没有**（`lxtmp/_adapt_headers.md`）。
     * 也就是说：这一族的判法跟 studycenter 相反，照别处的习惯带头就是 401。
     */
    private suspend fun adaptGet(pathWithQuery: String): JSONObject? {
        val jwt = fif.bareJwt() ?: return null
        return runCatching { fif.getAdapt("$STTP$pathWithQuery", jwt, "$STTP/adaptive/") }
            .getOrNull()?.let { if (passed(it)) it else null }
    }

    /* ── 发请求与解析的小工具 ── */

    /**
     * 六位签到码签到。
     *
     * 【取证】**只有等级①**：读的是官方 H5 的签到码组件（`lxtmp/sign_js.txt`、`sign_flow.txt`），
     * 它把所有内容压成一条调用 ——
     * `aiktSignIn.checkQrcodeHandler({signId, userName, signCode})`，
     * 而 api 定义里是
     * `post("$api/coursecenter-interaction/qrcodeV2/checkQrcodeHandler", qs.stringify(t))`，
     * 也就是 form-urlencoded，三个键。
     *
     * **零报文**：这一发在磁盘上没有一条真实请求/响应（`契约_AI课堂抓包.md` 也写着"签到提交零条"）。
     * 所以：
     * ① `signId` 不传 —— H5 那个组件是从路由 query 里拿的（链接带过来的），我们没有那条链接；
     *    服务端认不认"只有码+账号"这一发，**没验过**。
     * ② 判法照官方组件抄：**只认 `status == "error"` 为失败**，成功时不一定是 `"success"`。
     *    别套 `passed()`（那一族把空 status 也算过，会把这个口放宽）。
     * ③ 这是写操作，界面上由他按，不自动重试、不在后台试。
     */
    suspend fun signByCode(code: String): Result<String> = runCatching {
        val who = fif.ids()?.userName
        if (who.isNullOrBlank()) throw Exception("还没连上 AI 课堂，先去 AI 课堂那一屏点一次")
        val body = fif.postForm(
            "$STTP/coursecenter-interaction/qrcodeV2/checkQrcodeHandler",
            listOf("userName" to who, "signCode" to code),
            fif.authHeader(),
        )
        val root = JSONObject(body)
        if (root.optString("status") == "error") {
            throw Exception(root.optString("message").ifBlank { "这个签到码不对" })
        }
        root.optString("message").ifBlank { "签到成功" }
    }

    private suspend fun get(url: String): JSONObject? =
        runCatching { fif.getJson(url) }.getOrNull()?.let { if (passed(it)) it else null }

    private suspend fun post(url: String, form: List<Pair<String, String>>): JSONObject? =
        runCatching { JSONObject(fif.postForm(url, form, fif.authHeader())) }
            .getOrNull()?.let { if (passed(it)) it else null }

    /**
     * 这一族判成功看 `status` 字符串 `"success"`，个别口给 `"1"`。
     * 签到那一族按官方 H5 的判法是**只认 `"error"` 为失败**，但签到我们还没接（零报文），
     * 所以这里先不放宽 —— 真接签到那天单独开一个判法，别把整族都放宽成"什么都算过"。
     */
    private fun passed(root: JSONObject): Boolean {
        val status = root.optString("status")
        return status.isBlank() || status == "success" || status == "1"
    }

    private companion object {
        const val STTP = "https://sttp.fifedu.com"
        const val RESC = "https://resc.fifedu.com"

        fun enc(s: String): String = com.andaxin.core.net.formUrlEncode(s)

        /** 有的口 `data` 是对象（里面再挂 dataList），有的直接就是数组。两种都吃。 */
        fun data(root: JSONObject): JSONObject = root.optJSONObject("data") ?: JSONObject()

        fun arr(v: Any?): JSONArray = when (v) {
            is JSONArray -> v
            is JSONObject -> v.optJSONArray("dataList") ?: v.optJSONArray("list") ?: JSONArray()
            else -> JSONArray()
        }

        fun arrOrNull(v: Any?): JSONArray? = if (v is JSONArray) v else null

        fun objs(v: Any?): List<JSONObject> = objsOf(arr(v))

        fun objsOf(a: JSONArray): List<JSONObject> = (0 until a.length()).mapNotNull { a.optJSONObject(it) }

        fun firstOf(o: JSONObject, vararg keys: String): String =
            keys.asSequence().map { o.optString(it) }.firstOrNull { it.isNotBlank() }.orEmpty()

        fun sizeOf(bytes: Long): String = when {
            bytes <= 0 -> ""
            bytes > 1L shl 30 -> ((bytes / 1073741824.0 * 10).toLong() / 10.0).toString() + " GB"
            bytes > 1L shl 20 -> kotlin.math.round(bytes / 1048576.0).toInt().toString() + " MB"
            else -> (kotlin.math.round(bytes / 1024.0).toInt()).toString() + " KB"
        }

        /**
         * `2026-10-19T00:23:40.000+00:00` → 本机时区的 `10-19 08:23`。
         * 解不开就原样给回去 —— 宁可看见一串陌生的 ISO，也别看见一个空的时间段。
         */
        fun utc(s: String): String = runCatching {
            val t = kotlinx.datetime.Instant.parse(s)
                .toLocalDateTime(kotlinx.datetime.TimeZone.currentSystemDefault())
            (if (t.monthNumber < 10) "0" else "") + t.monthNumber + "-" +
                (if (t.dayOfMonth < 10) "0" else "") + t.dayOfMonth + " " +
                (if (t.hour < 10) "0" else "") + t.hour + ":" +
                (if (t.minute < 10) "0" else "") + t.minute
        }.getOrDefault(s)

    }
}

/** 一门课。`courseId`(32) 与 `teachClassId`(19) 是后面所有请求的入参，别拿同一个值填两个。 */
data class AiCourse(
    val id: String,
    val name: String,
    val teacher: String,
    val courseId: String,
    val teachClassId: String,
    val courseRecordId: String,
    val cover: String,
)

/** 学期。`id` 就是直录播那发要的 `termId`。 */
data class AiTerm(
    val id: String,
    val year: String,
    val num: String,
    val name: String,
    val selected: Boolean,
)

/** 这一层统一的"一行"形状：标题 + 副标题 + 右上角那几个字 + 详情要的原始键。 */
data class AiItem(
    val id: String,
    val title: String,
    val sub: String = "",
    val right: String = "",
    val extra: Map<String, String> = emptyMap(),
)

/**
 * 章节树上的一层（章或节）。
 *
 * 同一形状服务两处，来源不同：AI 课堂那门课的 `chapterMode`（18 键那棵树），
 * 和公开课的 `newCourseChapterList`（10 键那棵树）。两边的键名不一样但意思对得上，
 * 所以这里只留**两边都给得出**的栏位：`items`/`learned` 在第一章里分别来自
 * `itemCnt`/`learnedItemCnt`，在第二章里来自 `targetNumber`/`studiedNumber`。
 * `locked`/`hasNew` 只有 chapterMode 那侧有，公开课那侧给的是 `activityCount`。
 */
data class AiChapter(
    val id: String,
    val title: String,
    val level: Int,
    val items: Int = 0,
    val learned: Int = 0,
    val finished: Boolean = false,
    val locked: Boolean = false,
    val hasNew: Boolean = false,
    val activityCount: Int = 0,
    /** chapterMode 里那个 6 位的 `relCourseId`（校外资源课的号），公开课那侧没有。 */
    val relCourseId: String = "",
)

/** 公开课列表里的一门课。`uid`(32) 是给 adapt-app 那三条口用的，`shortId` 是校方自己那条短号。 */
data class OpenCourse(
    val id: String,
    val shortId: String,
    val uid: String,
    val name: String,
    val teacher: String,
    val cover: String,
    val signCount: Int,
    val teachClassId: String,
    val type: String,
    val schoolName: String,
)

/** 公开课详情。`courseDesc` 实测恒 null，所以这里没有"简介"这一栏。 */
data class AdaptCourse(
    val id: String,
    val name: String,
    val code: String,
    val subject: String,
    val teacherId: String,
    val cover: String,
    val mastery: Int,
    val graph: String,
)

/** 整门课学了多少个点位（真值：`studiedNumber=0 / targetNumber=215`）。 */
data class AdaptProgress(val studied: Int, val target: Int)

/**
 * 一门课的签到情况。四个分类的键名就是服务端那四个拼音（`chuqin/chidao/kuangke/qingjia`），
 * 不是我翻译出来的。
 *
 * `evidenced=false` 表示统计那一发根本没回对象（没登录、班号不对、或它挂了），
 * 这时候五个数全是 0 —— 界面必须说"没拿到"，不能说"一次都没签"。这两件事差很远。
 */
data class AiAttendance(
    val total: Int = 0,
    val present: Int = 0,
    val late: Int = 0,
    val absent: Int = 0,
    val leave: Int = 0,
    val records: List<AiItem> = emptyList(),
    val evidenced: Boolean = false,
)
