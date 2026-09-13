# WebSocket 协议

手机与 PC 之间用 **JSON 行** 通信，默认端口 `8787`。连接后必须先 `hello` 且 PIN 正确，才能发送 `text`。

## 手机 → PC

```json
{"type":"hello","pin":"123456"}
{"type":"text","text":"要上屏的内容","seq":1}
{"type":"ping"}
```

## PC → 手机

```json
{"type":"welcome","server":"SayType","ok":true}
{"type":"error","code":"bad_pin"}
{"type":"ack","seq":1,"ok":true,"method":"clipboard"}
{"type":"pong"}
```

## 错误码

| code | 含义 |
|------|------|
| `bad_pin` | PIN 错误 |
| `bad_json` | JSON 解析失败 |
| `not_hello` | 未完成 hello 握手 |
| `empty_text` | 文本为空 |
| `too_long` | 文本超长 |
| `inject_failed` | 剪贴板/粘贴注入失败 |
| `unknown_type` | 未知消息类型 |

## 安全约束

- 默认只监听局域网，不暴露公网
- PIN 每次启动随机生成，不进 git、不硬编码
- 日志不记录用户输入正文，只记长度与成功/失败
