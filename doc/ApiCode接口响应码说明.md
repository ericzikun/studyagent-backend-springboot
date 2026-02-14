# ApiCode 接口响应码枚举说明

所有 API 响应码统一在 `com.studyagent.common.api.ApiCode` 枚举中管理，**当前 API 返回的 statusMsg 采用英文 (messageEn)**。

## 使用方式

```java
// 无参数
Result.error(ApiCode.USER_NOT_LOGGED_IN);

// 带格式化参数
Result.error(ApiCode.QUOTA_EXCEEDED, 3);
Result.error(ApiCode.INVALID_PACKAGE_TYPE, "starter");
Result.error(ApiCode.FILE_UPLOAD_FAILED, ex.getMessage());
```

## 枚举定义（中英文双语言）

| 枚举常量 | code | messageEn | messageZh |
|---------|------|-----------|-----------|
| SUCCESS | 0 | Success | 成功 |
| USER_NOT_LOGGED_IN | 401 | User not logged in | 用户未登录 |
| PARAM_ERROR | 1001 | Parameter error | 参数错误 |
| PARAM_VALIDATION_FAILED | 1001 | Parameter validation failed: %s | 参数验证失败: %s |
| ILLEGAL_STATE | 1002 | Illegal state | 状态异常 |
| TASK_NOT_FOUND | 1003 | Task not found | 任务不存在 |
| NO_PERMISSION | 1004 | No permission | 无权限 |
| QUOTA_EXCEEDED | 1010 | Daily task submission limit reached (%d times). Please try again tomorrow. | 今日任务提交次数已达上限（%d 次），请明天再试 |
| FILE_UPLOAD_FAILED | 4000 | File upload failed: %s | 文件上传失败: %s |
| FILE_UPLOAD_STREAM_INTERRUPTED | 4001 | File upload failed: transfer interrupted. Please check your network. | 文件上传失败：上传过程中断 |
| FILE_UPLOAD_SIZE_EXCEEDED | 4002 | File upload failed: file size exceeds limit (max 100MB) | 文件上传失败：文件大小超过限制 |
| BAD_REQUEST | 400 | Bad request: %s | 请求错误: %s |
| INTERNAL_ERROR | 500 | Internal server error: %s | 服务器错误: %s |
| STRIPE_NOT_CONFIGURED | 500 | Stripe not configured | Stripe 未配置 |
| INVALID_PACKAGE_TYPE | 400 | Invalid package type: %s | 无效的套餐类型: %s |
| PRICE_CONFIG_ERROR | 500 | Price ID config error... | 套餐配置错误 |
| PRICE_NOT_FOUND | 400 | No valid price found for product: %s | 产品下没有找到有效的价格 ID |
| STRIPE_API_ERROR | 500 | Stripe error: %s | Stripe 错误: %s |
| PAYMENT_SESSION_CREATE_FAILED | 500 | Failed to create checkout session | 创建支付会话失败 |
| SESSION_ID_REQUIRED | 400 | sessionId is required | sessionId 参数不能为空 |
| SESSION_QUERY_FAILED | 9999 | Failed to query session status: %s | 查询会话状态失败 |
| UNKNOWN_ERROR | 9999 | Internal server error | 系统异常 |
| UNKNOWN_ERROR_WITH_MSG | 9999 | %s | %s |

## 新增/修改规范

1. 在 `ApiCode` 中添加新枚举，必须同时提供 `messageEn` 和 `messageZh`
2. 需要格式化参数时使用 `%s`、`%d` 等占位符
3. Controller 和 GlobalExceptionHandler 统一使用 `Result.error(ApiCode.XXX)` 或 `Result.error(ApiCode.XXX, args...)`
4. Service 层抛 BusinessException 时使用 `ApiCode.XXX.getCode()` 和 `ApiCode.XXX.getMessage()`
