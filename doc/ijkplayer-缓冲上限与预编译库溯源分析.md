# IJK 缓冲上限与预编译库溯源分析（TVBox-Suite）

- 分析日期：2026-09-14
- 分析对象：
  - `app/src/main/jniLibs/arm64-v8a/{libijkplayer.so,libijkffmpeg.so,libijksdl.so}`
  - `app/src/main/jniLibs/armeabi-v7a/*`（同上，本文以 arm64-v8a 为例取证）
  - `app/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java`（工程内 Java 封装）
  - `app/src/main/java/com/github/tvbox/osc/player/IjkmPlayer.java`（工程播放封装）
- 参照源码：`bilibili/ijkplayer` 的 `k0.8.8` tag 与 `master` 分支、`FFmpeg` 的 `n3.4` / `n4.0` tag
- 目标：
  1. 弄清「暂停后继续缓存」的真实上限与生效条件（为什么设置 256MB 没效果）；
  2. 判定工程内置 `.so` 到底来自官方 k0.8.8、官方 master，还是某个 fork 二次修改版；
  3. 评估自行编译官方库的成本 / 收益，并给出可选路径。

---

## 1. 结论速览

| # | 问题 | 结论 | 依据强度 |
|---|---|---|---|
| 1 | IJK 暂停后是否继续缓存 | **是**。read 线程会继续填 packet 队列，直到「队列总字节 > `max-buffer-size`」才停 | 源码 + 二进制取证 |
| 2 | 缓存上限是多少 | **15MB（15728640 字节）**，且这就是 native 允许的最大值（默认即最大值） | `.so` 内 AVOption 表实测：`min=0 / max=15728640 / default=15728640` |
| 3 | 为什么设 256MB 无效 | 超过 AVOption 的 `max`，`av_opt_set_int` 直接返回 `AVERROR(ERANGE)`，值被丢弃（且会中断同批 `player_opts` 的后续应用） | `libavutil/opt.c` 源码 + 实测 |
| 4 | 为什么运行时切 `infbuf` 无效 | IJK 的 option 只在 prepare 阶段被消费：`format_opts` → `avformat_open_input`，`player_opts` → `av_opt_set_dict(ffp, &ffp->player_opts)`；prepare 后再下发不生效 | 源码行号对照 |
| 5 | `infbuf` 属于哪一类选项 | **player 类**（`OPT_CATEGORY_PLAYER = 4`）。工程里 rtsp 分支用的 `setOption(1, "infbuf", 1)`（cat=1 = FORMAT）存疑 | `ffp_get_opt_dict()` 源码 |
| 6 | 能否「不设上限」 | **能**，`infbuf=1` 会跳过整个等待分支，read 线程一路读到 EOF（点播=整片进内存；直播=无限增长） | 源码 + 已实现为设置项 |
| 7 | 内置 `.so` 的来源 | **官方仓库 master（dev）分支 + 老 NDK 的预编译产物**；不是 k0.8.8 tag；未见 fork 二次修改证据 | FFmpeg 版本、ijklas 文件、选项表逐项比对 |
| 8 | 自建库是否值得 | 为了「把暂停缓存上限提高到 256MB」这一个目标：**不划算**（成本高、兼容红线多）。优先走「无上限」或「二进制 patch」 | 见第 10 节 |

---

## 2. 分析环境与工具

### 2.1 内置库清单

```bash
ls -la app/src/main/jniLibs/
du -sh app/src/main/jniLibs/*/*
```

```
arm64-v8a/   libijkffmpeg.so 12M   libijkplayer.so 416K   libijksdl.so 472K
armeabi-v7a/ libijkffmpeg.so 9.5M  libijkplayer.so 292K   libijksdl.so 216K
# 另有第三方 P2P 库：libp2p.so / libxl_stat.so / libxl_thunder_sdk.so（与本文无关）
```

要点：工程**只带 2 个 ABI**（`arm64-v8a`、`armeabi-v7a`），自建库时需要覆盖的也只有这两个（官方脚本默认 `all` 会编 5 个：`armv5 armv7a arm64 x86 x86_64`）。

### 2.2 使用的工具

| 用途 | 工具 | 说明 |
|---|---|---|
| 拉取官方源码 | `curl` + jsDelivr CDN | `raw.githubusercontent.com` 在本机不通，改用 `https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@<ref>/<path>`，`<ref>` 可以是 `master` 或 `k0.8.8` |
| 仓库文件清单 | jsDelivr data API | `https://data.jsdelivr.com/v1/packages/gh/Bilibili/ijkplayer@master?structure=flat` |
| 二进制取证 | `python3`（自写脚本，零依赖） | 解析 ELF64 头 / 程序头 / 节头 / `.rela.dyn`，应用 `R_AARCH64_RELATIVE`(1027)，还原 `AVOption[]` 与 `JNINativeMethod[]` |
| 字符串检索 | `python3` 正则 + `re.finditer` | 见下「踩坑」 |
| 版本比对 | `diff` | `diff k088_IjkMediaPlayer.java <工程内文件>` |

### 2.3 踩坑记录（重要，直接影响结论可信度）

1. **不要直接用 `grep` 看关键常量**：本次分析中 `grep` 的输出在工具通道里被改写过（例如 `grep -n "MAX_QUEUE_SIZE..."` 的匹配词被替换成 `n`，`echo ==== ...` 在 zsh 里被当成历史扩展而中断整条命令）。凡是「结论依赖具体数值」的地方，一律改用：

   ```bash
   python3 -c "
   src=open('ff_ffplay.c',encoding='utf-8',errors='ignore').read().split('\n')
   for i,l in enumerate(src,1):
       if 'MAX_QUEUE_SIZE' in l: print(i, l.strip())
   "
   ```

2. **`strings` 不够用**：`min/max` 是 `double`，默认值是 `i64`，都不在字符串里，必须解析结构体。

3. **`.so` 里的静态表被重定位**：`AVOption[]`、`JNINativeMethod[]` 这类含指针的 `static const` 表落在 `.data.rel.ro`，文件里存的是占位/加数，**必须先应用 `.rela.dyn` 中的 `R_AARCH64_RELATIVE`** 才能拿到真实字符串地址（见 6.2/6.3）。

4. **CDN 404 也是信息**：`ijklas.c` 在 `k0.8.8` 下取回 0 字节（不存在）、在 `master` 下取回 68935 字节，这是版本判定的硬证据之一。

---

## 3. IJK read 线程：什么时候停止继续读

### 3.1 关键代码（`ijkmedia/ijkplayer/ff_ffplay.c`）

> master 行号 / k0.8.8 行号对照（两处代码一致）

```c
/* master:3462-3480  /  k0.8.8:3459-3477 */
        /* if the queue are full, no need to read more */
        if (ffp->infinite_buffer<1 && !is->seek_req &&
#ifdef FFP_MERGE
              (is->audioq.size + is->videoq.size + is->subtitleq.size > MAX_QUEUE_SIZE
#else
              (is->audioq.size + is->videoq.size + is->subtitleq.size > ffp->dcc.max_buffer_size
#endif
            || (   stream_has_enough_packets(is->audio_st, is->audio_stream, &is->audioq, MIN_FRAMES)
                && stream_has_enough_packets(is->video_st,  is->video_stream,  &is->videoq,  MIN_FRAMES)
                && stream_has_enough_packets(is->subtitle_st,is->subtitle_stream,&is->subtitleq, MIN_FRAMES)))) {
            if (!is->eof) {
                ffp_toggle_buffering(ffp, 0);
            }
            /* wait 10 ms */
            SDL_LockMutex(wait_mutex);
            SDL_CondWaitTimeout(is->continue_read_thread, wait_mutex, 10);
            SDL_UnlockMutex(wait_mutex);
            continue;
        }
```

```c
/* master:3323-3324  /  k0.8.8:3320-3321 */
    if (ffp->infinite_buffer < 0 && is->realtime)
        ffp->infinite_buffer = 1;
```

### 3.2 `stream_has_enough_packets`（master:3034-3042）

```c
static int stream_has_enough_packets(AVStream *st, int stream_id, PacketQueue *queue, int min_frames) {
    return stream_id < 0 ||
           queue->abort_request ||
           (st->disposition & AV_DISPOSITION_ATTACHED_PIC) ||
#ifdef FFP_MERGE
           queue->nb_packets > MIN_FRAMES && (!queue->duration || av_q2d(st->time_base) * queue->duration > 1.0);
#endif
           queue->nb_packets > min_frames;
}
```

注：默认（非 `FFP_MERGE`）构建下，预处理后就是 `... || queue->nb_packets > min_frames;`（`FFP_MERGE` 分支那段三元/短路表达式本身写法也不严谨，属于上游遗留，不影响默认构建）。

### 3.3 相关宏（`ijkmedia/ijkplayer/ff_ffplay_def.h`，k0.8.8 与 master 完全相同，行 85-93）

```c
#define MAX_QUEUE_SIZE (15 * 1024 * 1024)      // 15728640 = 15MB
#define MAX_ACCURATE_SEEK_TIMEOUT (5000)
#ifdef FFP_MERGE
#define MIN_FRAMES 25
#endif
#define DEFAULT_MIN_FRAMES  50000
#define MIN_MIN_FRAMES      2
#define MAX_MIN_FRAMES      50000
#define MIN_FRAMES (ffp->dcc.min_frames)
```

### 3.4 推论

- 默认构建（未定义 `FFP_MERGE`）下，停止读的条件是：

  ```
  (audioq.size + videoq.size + subtitleq.size) > dcc.max_buffer_size
  || 每个流的 nb_packets > dcc.min_frames(默认 50000)
  ```

- `min_frames` 默认 50000，对普通点播几乎不可能触发（25fps 下 ≈ 2000 秒），因此**真正起约束的是字节数条件 `dcc.max_buffer_size`**。
- `dcc.max_buffer_size` 由 player 选项 `max-buffer-size` 控制；该选项的 `max` 就是 `MAX_QUEUE_SIZE = 15MB`（见第 6 节实测），所以**暂停后缓存上限 = 15MB，且无法通过合法参数调大**。
- `infinite_buffer >= 1`（`infbuf=1`）时整个 `if` 被短路 → read 线程不再等待 → 一路读到 EOF（这就是「不设上限」的实现原理）。

---

## 4. 选项如何生效：分类与消费时机

### 4.1 分类与落点

```c
/* ff_ffplay.c:4043-4046 */
    case FFP_OPT_CATEGORY_FORMAT:   return &ffp->format_opts;
    case FFP_OPT_CATEGORY_CODEC:    return &ffp->codec_opts;
    case FFP_OPT_CATEGORY_PLAYER:   return &ffp->player_opts;   // ← infbuf / max-buffer-size / min-frames 都在这里
```

```c
/* ff_ffplay.c:4159-4166 —— setOption 只写 dict，不立即应用 */
void ffp_set_option(FFPlayer *ffp, int opt_category, const char *name, const char *value)
{
    if (!ffp) return;
    AVDictionary **dict = ffp_get_opt_dict(ffp, opt_category);
    av_dict_set(dict, name, value, 0);
}
```

Java 侧类别常量（`IjkMediaPlayer`）：

```java
public static final int OPT_CATEGORY_FORMAT = 1;
public static final int OPT_CATEGORY_CODEC  = 2;
public static final int OPT_CATEGORY_SWS    = 3;
public static final int OPT_CATEGORY_PLAYER = 4;
```

### 4.2 消费点（均在 prepare 阶段，read 线程启动之前）

| 字典 | 消费位置 | 时机 |
|---|---|---|
| `format_opts` | `ff_ffplay.c:3122` `avformat_open_input(&ic, is->filename, is->iformat, &ffp->format_opts)` | 打开输入时 |
| `codec_opts` | `ff_ffplay.c:2869` `filter_codec_opts(ffp->codec_opts, ...)` / `3154` | 打开解码器时 |
| `player_opts` | `ff_ffplay.c:4298` `av_opt_set_dict(ffp, &ffp->player_opts)` | `ffp_prepare_async_l()` 内、`stream_open()` 之前 |

**推论**：`prepareAsync()` 之后再 `setOption()` 只会改 dict，不会被消费 → 之前在 `pause()` / `start()` 里动态切 `infbuf` 的做法**必然无效**。

### 4.3 越界值如何处理（FFmpeg n4.0 `libavutil/opt.c`）

```c
/* opt.c:97-105  write_number() */
    if (o->type != AV_OPT_TYPE_FLAGS &&
        (!den || o->max * den < num * intnum || o->min * den > num * intnum)) {
        num = den ? num * intnum / den : (num && intnum ? INFINITY : NAN);
        av_log(obj, AV_LOG_ERROR, "Value %f for parameter '%s' out of range [%g - %g]\n",
               num, o->name, o->min, o->max);
        return AVERROR(ERANGE);
    }
```

```c
/* opt.c:1565-1588  av_opt_set_dict2() —— 遇到错误即中断后续项 */
    while ((t = av_dict_get(*options, "", t, AV_DICT_IGNORE_SUFFIX))) {
        ret = av_opt_set(obj, t->key, t->value, search_flags);
        if (ret == AVERROR_OPTION_NOT_FOUND)
            ret = av_dict_set(&tmp, t->key, t->value, 0);
        if (ret < 0) {
            av_log(obj, AV_LOG_ERROR, "Error setting option %s to value %s.\n", t->key, t->value);
            av_dict_free(&tmp);
            return ret;          // ← 直接返回，后续 player 选项不再应用
        }
        ret = 0;
    }
```

**推论（比"设置无效"更严重）**：越界的 `max-buffer-size` 不仅自身被丢弃，还会让 `player_opts` 中**排在它后面的选项**一并不被应用（按 dict 插入顺序）。因此工程侧必须对用户输入做钳制——这也是本次修改把用户值 clamp 到 15MB 的原因。

---

## 5. 从 `.so` 还原 AVOption 表（二进制取证）

### 5.1 思路

选项的 `min/max/default` 决定「参数能不能生效」，但这些字段是 `double` / `i64`，不在字符串里。做法：把 `.so` 的 `AVOption[]` 表按结构解析出来。

`AVOption`（arm64）内存布局推导：

| 偏移 | 字段 | 类型 |
|---|---|---|
| +0 | name | `const char *`（8B） |
| +8 | help | `const char *`（8B） |
| +16 | offset | `int` |
| +20 | type | `enum`（4B） |
| +24 | default_val | union（8B，`AV_OPT_TYPE_INT` 时按 `int64` 读） |
| +32 | min | `double` |
| +40 | max | `double` |
| +48 | flags | `int` |
| +56 | unit | `const char *`，结构体对齐到 64B |

### 5.2 第一版失败原因

最初直接在文件字节流里搜索「字符串虚拟地址的 8 字节小端表示」，结果搜不到——因为 `.so` 是 `ET_DYN`，静态表里的指针需要重定位，表位于 `.data.rel.ro`，文件里存的是加数/占位，**真实值在 `.rela.dyn` 的 `R_AARCH64_RELATIVE`(1027) 条目里**。

### 5.3 可用脚本（`probe2.py`，零依赖，可直接复现）

```python
import struct

path = 'app/src/main/jniLibs/arm64-v8a/libijkplayer.so'
data = open(path, 'rb').read()

# --- ELF64 ---
e_shoff, = struct.unpack_from('<Q', data, 0x28)
e_shentsize, e_shnum, e_shstrndx = struct.unpack_from('<HHH', data, 0x3A)

def sh(i):
    o = e_shoff + i * e_shentsize
    name, typ, flags, addr, off, size, link, info, align, entsize = struct.unpack_from('<IIQQQQIIQQ', data, o)
    return dict(typ=typ, off=off, size=size, entsize=entsize)

secs = [sh(i) for i in range(e_shnum)]
e_phoff, = struct.unpack_from('<Q', data, 0x20)
e_phentsize, e_phnum = struct.unpack_from('<HH', data, 0x36)
segs = []
for i in range(e_phnum):
    o = e_phoff + i * e_phentsize
    t, fl, po, pv, pa, pfs, pms, al = struct.unpack_from('<IIQQQQQQ', data, o)
    if t == 1:  # PT_LOAD
        segs.append((po, pfs, pv, pms))

def va2off(v):
    for po, pfs, pv, pms in segs:
        if pv <= v < pv + pms and v - pv < pfs:
            return po + (v - pv)
    return None

# --- 应用 R_AARCH64_RELATIVE ---
rela = {}
for s in secs:
    if s['typ'] == 4 and s['entsize']:      # SHT_RELA
        for i in range(s['size'] // s['entsize']):
            o = s['off'] + i * s['entsize']
            r_off, r_info, r_add = struct.unpack_from('<QQq', data, o)
            if (r_info & 0xffffffff) == 1027:
                rela[r_off] = r_add

def cstrva(va):
    o = va2off(va)
    if o is None:
        return None
    return data[o:data.index(b'\x00', o)].decode('utf-8', 'replace')

# --- 定位：help 字符串 → 表项 base = help_slot - 8 ---
targets = {
    'max buffer size should be pre-read': 'max-buffer-size',
    'minimal frames to stop pre-reading': 'min-frames',
    "don't limit the input buffer size (useful with realtime streams)": 'infbuf',
}
for help_text, opt in targets.items():
    idx = data.find(help_text.encode() + b'\x00')
    help_va = None
    for po, pfs, pv, pms in segs:
        if po <= idx < po + pfs:
            help_va = pv + (idx - po)
    for slot, addend in rela.items():
        if addend != help_va:
            continue
        base = slot - 8                       # 该项的起始 vaddr
        o = va2off(base)
        name = cstrva(rela.get(base, 0))
        off_, typ = struct.unpack_from('<iI', data, o + 16)
        dflt, = struct.unpack_from('<q', data, o + 24)
        mn, mx = struct.unpack_from('<dd', data, o + 32)
        fl, = struct.unpack_from('<i', data, o + 48)
        print('0x%x %-18s type=%d default=%d min=%g max=%g flags=0x%x'
              % (base, name, typ, dflt, mn, mx, fl))
```

### 5.4 实测结果（arm64-v8a / libijkplayer.so）

| 选项 | entry vaddr | type | default | min | max | flags |
|---|---|---|---|---|---|---|
| `infbuf` | `0x72750` | 1 (INT) | 0 | 0 | 1 | 0x2 |
| `max-buffer-size` | `0x72b50` | 1 (INT) | **15728640** | 0 | **15728640** | 0x2 |
| `min-frames` | `0x72b90` | 1 (INT) | **50000** | 2 | 50000 | 0x2 |
| `first-high-water-mark-ms` | `0x72bd0` | 1 | 100 | 100 | 5000 | 0x2 |
| `next-high-water-mark-ms` | `0x72c10` | 1 | 1000 | 100 | 5000 | 0x2 |
| `last-high-water-mark-ms` | `0x72c50` | 1 | 5000 | 100 | 5000 | 0x2 |
| `packet-buffering` | `0x72c90` | 1 | 1 | — | — | 0x2 |
| `start-on-prepared` | `0x72ad0` | 1 | 1 | 0 | 1 | 0x2 |
| `video-pictq-size` | `0x72b10` | 1 | 3 | 3 | 16 | 0x2 |
| `max-fps` | `0x728d0` | 1 | 31 | -1 | 121 | 0x2 |
| `framedrop` | `0x72790` | 1 | 0 | -1 | 120 | 0x2 |
| `volume` | `0x72690` | 1 | 100 | 0 | 100 | 0x2 |
| `loop` | `0x72710` | 1 | 1 | INT32_MIN | INT32_MAX | 0x2 |
| `seek-at-start` | `0x727d0` | 2 (INT64) | 0 | 0 | INT32_MAX | 0x2 |

结论：**`max-buffer-size` 的默认值 = 最大值 = 15MB**。这解释了两件事：

1. 不设置时，IJK 已经跑在最大水位，任何「调大」的尝试（200MB / 256MB）都会越界被拒；
2. 用户只能「调小」或「不设上限」，无法「调大到 15MB 以上」。

### 5.5 完整选项表比对（0x725d0 起，共 59 项）

从 `.so` 中还原出的 `ffp_context_options` 顺序与官方 `ff_ffplay_options.h` **完全一致**（脚本校验会漏掉 `af` / `vf0` 两项，因为它们的 help 指针校验条件不同，属脚本过滤问题，不是库缺失）：

```
an vn nodisp volume fast loop infbuf framedrop seek-at-start subtitle rdftspeed
find_stream_info max-fps overlay-format fcc-_es2 fcc-i420 fcc-yv12 fcc-rv16 fcc-rv24 fcc-rv32
start-on-prepared video-pictq-size max-buffer-size min-frames
first-high-water-mark-ms next-high-water-mark-ms last-high-water-mark-ms packet-buffering
sync-av-start iformat no-time-adjust preset-5-1-center-mix-level
enable-accurate-seek accurate-seek-timeout skip-calc-frame-rate get-frame-mode
async-init-decoder video-mime-type
videotoolbox videotoolbox-max-frame-width videotoolbox-async videotoolbox-wait-async
videotoolbox-handle-resolution-change
mediacodec mediacodec-auto-rotate mediacodec-all-videos mediacodec-avc mediacodec-hevc
mediacodec-mpeg2 mediacodec-mpeg4 mediacodec-handle-resolution-change
opensles soundtouch mediacodec-sync mediacodec-default-name ijkmeta-delay-init render-wait-start
```

官方 `k0.8.8` 与 `master` 的 `ffp_context_options` **选项集合完全相同（59 项，无增删）**，因此选项表不能区分这两个版本（区分依据见第 7 节）。

紧邻其后的另一张表（属于 `ijklas` demuxer 的 AVClass）被一并扫到：

```
user-agent  headers  manifest_string  abr_history_data  device-network-type  liveAdaptConfig  session_id
```

这 7 个名字在 `ff_ffplay.c` / `ff_ffplay_options.h` 中**都不存在**，但全部命中 `ijkmedia/ijkplayer/ijkavformat/ijklas.c`（见 7.4）——排除「fork 私加选项」的猜测。

---

## 6. 内置 `.so` 溯源：官方 master（非 k0.8.8，未见 fork 痕迹）

### 6.1 工具链字符串

```bash
python3 -c "
import re
d=open('app/src/main/jniLibs/arm64-v8a/libijkplayer.so','rb').read()
s=set(m.group().decode('utf-8','replace') for m in re.finditer(rb'[\x20-\x7e]{6,}',d))
for x in sorted(s):
    if 'clang' in x or 'GCC:' in x or '/Users/' in x or '/home/' in x: print(repr(x[:120]))
"
```

```
'Android clang version 3.8.256229  (based on LLVM 3.8.256229)'
'GCC: (GNU) 4.9.x 20150123 (prerelease)'      # 来自 libijkffmpeg.so
```

推论：`clang 3.8.256229` + `gcc 4.9` 对应 **NDK r13b/r14b** 时代，与官方 `ijkplayer` 构建脚本（要求 NDKr10e+，见 10.1）匹配。注意：本机未发现 `/Users/...` 之类的构建路径字符串，无法据此定位具体作者，只能靠版本特征判定。

### 6.2 FFmpeg 版本（决定性证据）

```bash
python3 -c "
import re
d=open('app/src/main/jniLibs/arm64-v8a/libijkffmpeg.so','rb').read()
s=set(m.group().decode('utf-8','replace') for m in re.finditer(rb'[\x20-\x7e]{4,}',d))
for x in sorted(s):
    if re.search(r'Lavf\d', x): print(repr(x))
"
# → 'Lavf58.12.100'
```

对照 FFmpeg 官方 `libavformat/version.h`：

| FFmpeg tag | LIBAVFORMAT_VERSION |
|---|---|
| `n3.4` | `57.83.100` |
| `n4.0` | `58.12.100` ✅ |

→ 内置 ffmpeg 是 **FFmpeg 4.0**。

### 6.3 官方两个 ref 各自编译哪个 FFmpeg

```bash
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@k0.8.8/init-android.sh" | sed -n '18,28p'
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/init-android.sh" | sed -n '18,28p'
```

```bash
# k0.8.8
IJK_FFMPEG_COMMIT=ff3.4--ijk0.8.7--20180103--001     # FFmpeg 3.4
# master
IJK_FFMPEG_COMMIT=ff4.0--ijk0.8.8--20210426--001     # FFmpeg 4.0 ✅ 与 Lavf58.12.100 一致
```

→ **排除 k0.8.8 tag**。

### 6.4 `ijklas` 归属（第二个决定性证据）

```bash
for v in k0.8.8 master; do
  echo "=== $v ==="
  curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@$v/ijkmedia/ijkplayer/ijkavformat/ijklas.c" | wc -c
done
```

```
k0.8.8 → 0 字节（404，文件不存在）
master → 68935 字节
```

且 `.so` 中扫到的那 7 个「非 ff_ffplay 选项」在 `master/ijkavformat/ijklas.c` 中全部命中：

```
manifest_string 6   abr_history_data 4   device-network-type 2
liveAdaptConfig 2   session_id 20        user-agent 1   headers 8
```

→ 内置库带有 `ijklas`（Bilibili Live Adaptive Streaming），该组件**只存在于 master 分支**。

### 6.5 协议 / 特性清单（全部属于官方 master）

```bash
python3 -c "
import re
for p in ['libijkffmpeg.so','libijkplayer.so']:
    d=open('app/src/main/jniLibs/arm64-v8a/'+p,'rb').read()
    s=set(m.group().decode('utf-8','replace') for m in re.finditer(rb'[\x20-\x7e]{6,}',d))
    for k in ['ijksegment','ijktcphook','ijklivehook','ijkinject','ijkhttphook','ijkurlhook',
              'ijklongurl','ijkmediadatasource','cache_max_capacity','cache_file_path',
              'cache_map_path','async','openssl','x264','ijklas']:
        print(p, k, sum(1 for x in s if k in x))
"
```

命中：`ff_ijklas_demuxer`、`ff_ijklivehook_demuxer`、`ijkav_register_async_protocol`、`ijkav_register_ijkmediadatasource_protocol`、`ijkav_register_ijkhttphook_protocol`、`cache_file_path` / `cache_map_path` / `cache_max_capacity`（磁盘缓存）、openssl、x264。官方 master 的 `ijkmedia/ijkplayer/ijkavformat/` 目录确认包含：`ijkasync.c`、`ijkiocache.c`、`ijklas.c`、`ijklivehook.c`、`ijklongurl.c`、`ijkmediadatasource.c`、`ijksegment.c`、`ijkurlhook.c`、`ijkiourlhook.c` 等。

### 6.6 判定

**内置 `.so` = 官方仓库 master（dev）分支源码 + 官方构建脚本 + NDK r13/r14 编译的预编译产物（第三方打包，dkplayer/TVBox 生态常见做法）。**

- 不是 `k0.8.8` tag（FFmpeg 版本 + ijklas 双重证据）；
- **没有发现 fork 二次修改的证据**：ffp 选项表 59 项名称 / 顺序 / 默认值 / min / max 与官方源码逐项一致；协议与特性集合与官方 master 一致；
- 残留不确定性：`libijkplayer.so` 未保留调试路径 / build-id 对照信息，理论上无法排除「少量 patch + 未改选项表」的可能。若需 100% 确认，只能自建一份并做逐函数对比（成本极高，不建议）。

---

## 7. 工程侧的耦合面（重建 native 的兼容红线）

### 7.1 JNI 方法表（从 `.so` 还原，共 40 项）

脚本思路：`JNINativeMethod{name, signature, fnPtr}` 每项 24 字节，三项都落在同一批 `R_AARCH64_RELATIVE` 重定位里，按「name/sig 为可打印字符串且 sig 以 `(` 开头」过滤。

| vaddr | 方法 | 签名 |
|---|---|---|
| 0x76018 | av_base64_encode | `([B)Ljava/lang/String;` |
| 0x76030 | _setDataSource | `(Ljava/lang/String;[Ljava/lang/String;[Ljava/lang/String;)V` |
| 0x76048 | _setDataSourceFd | `(I)V` |
| 0x76060 | _setDataSource | `(Ltv/danmaku/ijk/media/player/misc/IMediaDataSource;)V` |
| 0x76078 | _setAndroidIOCallback | `(Ltv/danmaku/ijk/media/player/misc/IAndroidIO;)V` |
| 0x76090 | _setVideoSurface | `(Landroid/view/Surface;)V` |
| 0x760a8 | _prepareAsync | `()V` |
| 0x760c0 | _start | `()V` |
| 0x760d8 | _stop | `()V` |
| 0x760f0 | seekTo | `(J)V` |
| 0x76108 | _pause | `()V` |
| 0x76120 | isPlaying | `()Z` |
| 0x76138 | getCurrentPosition | `()J` |
| 0x76150 | getDuration | `()J` |
| 0x76168 | _release | `()V` |
| 0x76180 | _reset | `()V` |
| 0x76198 | setVolume | `(FF)V` |
| 0x761b0 | getAudioSessionId | `()I` |
| 0x761c8 | native_init | `()V` |
| 0x761e0 | native_setup | `(Ljava/lang/Object;)V` |
| 0x761f8 | native_finalize | `()V` |
| 0x76210 | _setOption | `(ILjava/lang/String;Ljava/lang/String;)V` |
| 0x76228 | _setOption | `(ILjava/lang/String;J)V` |
| 0x76240 | _getColorFormatName | `(I)Ljava/lang/String;` |
| 0x76258 | _getVideoCodecInfo | `()Ljava/lang/String;` |
| 0x76270 | _getAudioCodecInfo | `()Ljava/lang/String;` |
| 0x76288 | _getMediaMeta | `()Landroid/os/Bundle;` |
| 0x762a0 | _setLoopCount | `(I)V` |
| 0x762b8 | _getLoopCount | `()I` |
| 0x762d0 | _getPropertyFloat | `(IF)F` |
| 0x762e8 | _setPropertyFloat | `(IF)V` |
| 0x76300 | _getPropertyLong | `(IJ)J` |
| 0x76318 | _setPropertyLong | `(IJ)V` |
| 0x76330 | _setStreamSelected | `(IZ)V` |
| 0x76348 | native_profileBegin | `(Ljava/lang/String;)V` |
| 0x76360 | native_profileEnd | `()V` |
| 0x76378 | native_setLogLevel | `(I)V` |
| 0x76390 | native_setReqLevel | `(I)V` |
| 0x763a8 | native_setDot | `(I)V` |
| 0x763c0 | _setFrameAtTime | `(Ljava/lang/String;JJII)V` |

重建后若任一签名缺失/变更 → `UnsatisfiedLinkError` 或静默失效。

### 7.2 property 常量（本工程 `IjkMediaPlayer.java` 与 native 共用，必须一致）

本次新增的缓存显示依赖：

```java
public static final int FFP_PROP_INT64_VIDEO_CACHED_BYTES = 20007;
public static final int FFP_PROP_INT64_AUDIO_CACHED_BYTES = 20008;
public static final int FFP_PROP_INT64_TCP_SPEED          = 20200;
```

### 7.3 本工程对 Java 封装的改动（不能直接用官方 java 覆盖）

```bash
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@k0.8.8/\
android/ijkplayer/ijkplayer-java/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java" -o /tmp/k088.java
diff /tmp/k088.java app/src/main/java/tv/danmaku/ijk/media/player/IjkMediaPlayer.java | wc -l
# → 544
```

- 绝大多数是格式化差异（对齐空格、`{ "name", OPTION_INT(...) }` 排版）；
- 但本工程**确实新增了逻辑**：`import com.github.tvbox.osc.base.App`、`com.github.tvbox.osc.util.FileUtils`、`java.net.HttpURLConnection` 等，并在 `IjkMediaPlayer.java:427-441` 附近用 `HttpURLConnection` 主动 GET 播放地址（用于地址/内容处理）。
- → 直接用官方 Java 替换会丢失这块自定义行为。

### 7.4 协议白名单（本工程：`IjkmPlayer.java:153`）

```java
mMediaPlayer.setOption(IjkMediaPlayer.OPT_CATEGORY_FORMAT, "protocol_whitelist",
    "ijkio,ffio,async,cache,crypto,file,dash,http,https,ijkhttphook,ijkinject,ijklivehook,"
  + "ijklongurl,ijksegment,ijktcphook,pipe,rtp,tcp,tls,udp,ijkurlhook,data,concat,subfile,ffconcat");
```

重建后若 ffmpeg 编译裁剪（`config/module-*.sh`）不含 `async` / `cache` / `dash` / `concat` 等，会直接导致协议不可用。

---

## 8. 现象解释与本次落地改动

### 8.1 现象 → 原因

| 现象 | 原因 |
|---|---|
| 暂停后速率先跑一会儿再归零 | read 线程继续填队列，直到总字节 > 15MB 才 wait → **功能正常，只是上限小** |
| 进度条灰色缓存只有一小段 | 15MB 换算成时间取决于码率（如 2MB/s ≈ 7.5 秒），视觉上很短 |
| 设 256MB 没变化 | 越界被 `av_opt_set_int` 拒绝（`ERANGE`），值根本没进去 |
| 运行时切 `infbuf` 无效果 | option 只在 prepare 阶段消费 |

### 8.2 代码改动清单（已编译验证：`./gradlew :app:compileArm64NormalDebugJavaWithJavac` → BUILD SUCCESSFUL）

| 文件 | 改动 |
|---|---|
| `player/IjkmPlayer.java` | ① 删除无效的运行时 `infbuf` 切换（Handler + pause/start/release/reset 钩子）；② `applyIjkMaxBufferOverride()`：用户值 clamp 到 15MB；新增 `IJK_MAX_BUFFER_UNLIMITED(-1)` 分支 → `setOption(OPT_CATEGORY_PLAYER, "infbuf", 1)`；③ 补充完整机制注释（含源码行号/条件） |
| `util/HawkUtils.java` | 新增 `IJK_MAX_BUFFER_LIMIT_MB=15`、`IJK_MAX_BUFFER_UNLIMITED=-1`；`getIJKMaxBufferSize()` 钳制；选项循环改为 `{0,2,4,8,15,-1}`；描述新增「无上限(慎用)」 |
| `player/controller/BaseController.java` | 新增 `mCacheTextTop/Topr/Hide`（tag：`play_cache_top`/`_topr`/`_top_hide`）；`updateCacheText()` 复用 1Hz `mRunnable`，`bufferedBytes<0` 时留空，文本未变跳过 `setText` |
| `ijk/IjkPlayer.java` | 新增 `getBufferedBytes()` = `getVideoCachedBytes() + getAudioCachedBytes()`（即 packet 队列已缓存字节） |
| `player/AbstractPlayer.java` | 新增非抽象方法 `getBufferedBytes()`，默认 `-1`（Exo/系统播放器不支持时不显示） |
| `player/BaseVideoView.java`、`controller/MediaPlayerControl.java`、`controller/ControlWrapper.java` | 打通 `getBufferedBytes()` 调用链 |
| `ui/activity/LivePlayActivity.java` | 网速文本追加 ` \| x.xxMB` |
| `res/layout/player_vod_control_view.xml`、`player_live_control_view.xml` | 在 `Mbps` 后新增缓存 TextView；两处速度容器由固定 `@dimen/vs_120` 改为 `wrap_content`（标题有 `layout_weight=1` + `ellipsize`，不会被顶歪） |
| `README.md` | 更正缓冲上限为 15MB、说明「无上限」与 native 限制 |

### 8.3 刷新频率的取舍（设计说明）

- 复用已有 1Hz 定时器，**不新增线程/定时器**；
- 每秒增量：2 次 JNI property 读取 + 1 次 `String.format`；文本未变化时跳过 `setText`（避免无意义重绘）；
- 粒度选择：秒级足够（缓冲变化的视觉可辨尺度是数百毫秒以上），若改到「每个网络包」级别，代价是频繁 `setText` + JNI 调用，收益为零。

---

## 9. 自建库：成本 / 收益与可选路径

### 9.1 官方构建流程（复现用）

```bash
# 0) 环境：NDK r10e ~ r14（内置库为 clang 3.8 → 对应 r13/r14）
export ANDROID_NDK=/path/to/android-ndk-r14b
export ANDROID_SDK=/path/to/android-sdk

git clone https://github.com/Bilibili/ijkplayer.git
cd ijkplayer
# git checkout k0.8.8        # 若要用 tag；master 默认 FFmpeg 4.0

./init-android.sh            # 拉 FFmpeg(master: ff4.0--ijk0.8.8--20210426--001)
./init-android-openssl.sh    # 拉 OpenSSL(master: OpenSSL_1_0_2q)
./init-config.sh             # 默认 cp config/module-lite.sh config/module.sh

cd android/contrib
./compile-openssl.sh clean
./compile-openssl.sh all
./compile-ffmpeg.sh clean
./compile-ffmpeg.sh armv7a          # 也可 all / all32 / arm64 / x86 / x86_64
./compile-ffmpeg.sh arm64
cd ..
./compile-ijk.sh all                # 产出 libijkffmpeg.so / libijkplayer.so / libijksdl.so
```

官方脚本路径与关键约定（来自仓库清单与脚本本身）：

- `/init-android.sh`：`IJK_FFMPEG_UPSTREAM=https://github.com/Bilibili/FFmpeg.git`，`IJK_FFMPEG_LOCAL_REPO=extra/ffmpeg`
- `/init-android-openssl.sh`：`IJK_OPENSSL_COMMIT=OpenSSL_1_0_2q`
- `/android/contrib/compile-ffmpeg.sh`：`FF_ACT_ARCHS_ALL="armv5 armv7a arm64 x86 x86_64"`
- `/android/contrib/tools/do-detect-env.sh`：通过 `RELEASE.TXT`（老 NDK）或 `source.properties`（新 NDK）判定 `IJK_NDK_REL`，并校验 `toolchains/arm-linux-androideabi-4.9` 存在；不满足时报 `You need the NDKr10e or later`

### 9.2 已知坑

1. **NDK 版本窗口窄**：脚本按 r10e~r14 设计；新版 NDK（r19+）移除了 `gcc` 工具链、`sys/sysctl.h`、`platforms/android-9`，直接失败。官方源码最后一次更新停在 2021 年，未适配新 NDK。
2. **macOS 宿主**：老 NDK 的预编译工具链是 x86_64 二进制 → Apple Silicon 需 Rosetta 2；`sed -i` / `sort` 等 BSD 与 GNU 差异、Xcode 15 的 `-lgcc`、旧汇编语法都会踩。
3. **依赖源可用性**：需能从 GitHub 拉取 `Bilibili/FFmpeg`（tag `ff4.0--ijk0.8.8--20210426--001`）与 `Bilibili/openssl`（`OpenSSL_1_0_2q`）；若镜像不可用需手工替换。
4. **耗时**：首次跑通通常 0.5~2 天；之后全量（2 个 ABI）约 30~90 分钟。
5. **兼容红线**（见第 7 节）：JNI 40 项签名、`FFP_PROP_*` 常量、`OnNativeInvoke` 参数、本工程对 Java 封装的自定义改动、协议白名单与 ffmpeg 裁剪模块必须逐一核对，否则崩溃或静默失效。

### 9.3 成本 / 收益

| 维度 | 说明 |
|---|---|
| 收益（只有落在 native 的需求才需要） | ① 把 `MAX_QUEUE_SIZE`（15MB）抬到任意值；② 改造 read 线程等待逻辑（例如「按可用内存/文件长度设上限的深度预读」）；③ 新增 Java 侧拿不到的 property；④ 升级 FFmpeg/OpenSSL（https、m3u8、新编码兼容性）；⑤ 修复上游 bug |
| 成本 | 高：老 NDK 环境 + 脚本改造（0.5~2 天）+ 每次全量编译 30~90 分钟 + 完整回归（HLS/https/硬解/seek）+ JNI/常量/Java 三方对齐 |
| 单点目标（暂停缓存到 256MB）的性价比 | **低**。该需求可先用「无上限」满足；若要「有上限但 >15MB」，优先考虑 9.4-B |

### 9.4 四条可选路径（按性价比排序）

**A. 用现成的「无上限」（已实现，零编译成本）**
- 设置项选「无上限」→ prepare 前下发 `setOption(OPT_CATEGORY_PLAYER, "infbuf", 1)`；
- 效果：缓存一路涨到整片结束；暂停后也继续涨；
- 代价：内存无上限（点播≈整片大小，直播持续涨到 OOM）；
- 验证方法：播放时看右上角 `xx MB` 是否持续突破 15MB。

**B. 二进制 patch 现有 `.so`（十几分钟，未实施、需自担风险）**
- 依据：`max_buffer_size` 在源码中只出现在 `ff_ffplay.c:3467` 一处判断，无二次钳制；只要放行 AVOption 的 `max`，`setOption` 就能写入任意 ≤2GB 的值。
- arm64 位置：`max-buffer-size` 表项 vaddr `0x72b50`，`max` 字段 = `+40`（8 字节 `double`，现为 `15728640.0`）。
- 步骤（`armeabi-v7a` 需按 4 字节指针 + 32 位结构体布局重新定位）：
  1. 用第 5.3 节的 `va2off()` 把 `0x72b50+40` 换算成文件偏移；
  2. 校验该 8 字节 == `struct.pack('<d', 15728640.0)`；
  3. 改写为 `struct.pack('<d', float(目标字节数))`（如 256MB = 268435456.0）；
  4. 用第 5.3 节脚本回读，确认 `max` 已变化、其余字段未动。
- 代价：非正规手段、升级/换库即失效、每个 ABI 需单独处理；好处是不需要任何编译环境。

**C. 用社区维护的分支重新编译（长期掌握，省一半时间）**
- 例如已适配新 NDK 的 ijk 后继项目（`debugly/ijkplayer`、`FSPlayer` 等），比硬啃 2021 年停更的官方脚本更现实；
- 仍需解决第 7 节的兼容红线。

**D. 完全不碰 native：本地代理 + 磁盘预取**
- 工程已依赖 `xyz.doikki.android.dkplayer:videocache`（AndroidVideoCache）；
- 由本地代理在播放器暂停时继续下载到磁盘（容量可控、LRU 淘汰），与 IJK 的 15MB 内存队列解耦；
- 风险：m3u8 分片绝对 URL 需改写、请求头透传、seek 的 Range 处理。

---

## 10. 复现命令速查

```bash
# 1. 取源码（raw.githubusercontent 不通时用 jsDelivr）
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/ijkmedia/ijkplayer/ff_ffplay.c"     -o ff_ffplay.c
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/ijkmedia/ijkplayer/ff_ffplay_def.h" -o ff_ffplay_def.h
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/ijkmedia/ijkplayer/ff_ffplay_options.h" -o ff_ffplay_options.h
curl -sL "https://cdn.jsdelivr.net/gh/FFmpeg/FFmpeg@n4.0/libavutil/opt.c" -o opt.c
curl -sL "https://cdn.jsdelivr.net/gh/FFmpeg/FFmpeg@n4.0/libavformat/version.h"

# 2. 文件清单 / 版本归属
curl -sL "https://data.jsdelivr.com/v1/packages/gh/Bilibili/ijkplayer@master?structure=flat"
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/init-android.sh"         | sed -n '18,28p'
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@k0.8.8/init-android.sh"         | sed -n '18,28p'
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@master/ijkmedia/ijkplayer/ijkavformat/ijklas.c" | wc -c
curl -sL "https://cdn.jsdelivr.net/gh/Bilibili/ijkplayer@k0.8.8/ijkmedia/ijkplayer/ijkavformat/ijklas.c" | wc -c

# 3. 二进制取证：见第 5.3 节脚本（AVOption）与第 7.1 节思路（JNINativeMethod）

# 4. 关键代码定位（用 python，不用 grep）
python3 -c "
src=open('ff_ffplay.c',encoding='utf-8',errors='ignore').read().split('\n')
for i,l in enumerate(src,1):
    if any(k in l for k in ['infinite_buffer<1','av_opt_set_dict','FFP_OPT_CATEGORY_PLAYER','avformat_open_input']):
        print(i, l.strip()[:120])
"
```

---

## 11. 附录：遗留疑问 / 后续可验证项

1. **`infbuf` 的 category 实测**：工程 `IjkmPlayer` 里 rtsp 分支用的是 `setOption(1, "infbuf", 1)`（FORMAT 类）。按源码它应在 PLAYER 类；理论上该行无效。可在 rtsp 源上对比 `setOption(4, "infbuf", 1)` 的行为差异确认。
2. **`min_frames` 是否真的不触发**：本工程 `.so` 中 `min-frames` 默认 50000（与源码一致），推论为「字节数条件主导」。可用新增的缓存显示做端到端验证：缓冲是否稳定停在 ≈15.00MB。
3. **`av_opt_set_dict2` 中断行为的实际影响面**：越界值会中断后续 `player_opts`；若将来再出现「某个 player 选项莫名失效」，优先排查是否有越界项插在它前面。
4. **patch 方案的 ABI 差异**：`armeabi-v7a` 的表项布局（4 字节指针、结构体对齐）未实测，若采用方案 B 需先按 32 位布局重新定位并回读校验。
