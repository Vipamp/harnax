/**
 * 调度域的业务码，前端只留这一份。
 *
 * 之前 `40902` 在列表页和表单页各声明了一遍，理由是"从列表页 import 会成环"——放进这个叶子模块就没有环，
 * 两个页面都从这里取。每个常量都标了对应的后端出处，改这里只应跟着后端改。
 */

/**
 * 对应后端 `SchedulerController.CODE_EXECUTION_IN_PROGRESS`：这个任务已经有一次执行在跑（或是别的实例
 * 先抢到了集群锁），调用方应该轮询而不是重试。
 */
export const CODE_EXECUTION_IN_PROGRESS = 40901;

/**
 * 对应后端 `AgentTaskServiceImpl.CODE_SCHEDULER_SYNC_FAILED`：数据已经提交，只是没有任何 scheduler 实例
 * 确认重载成功。用户要的那次操作本身并没有失败。
 */
export const CODE_SCHEDULER_SYNC_FAILED = 40902;

/**
 * 对应后端 `SchedulerController.CODE_SCHEDULER_DISABLED`：这台实例 `scheduler.enabled=false`，不接受任何
 * 调度写请求——请求发错了节点，重试和回滚都不是调用方该做的事。
 */
export const CODE_SCHEDULER_DISABLED = 40903;
