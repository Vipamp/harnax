// @ts-ignore
/* eslint-disable */
import { request } from '@umijs/max';

/** 获取定时任务列表 GET /admin/jobs/list */
export async function getJobPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    keyword?: string;
    jobStatus?: number;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/jobs/list', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 获取定时任务详情 GET /admin/jobs/${id} */
export async function getJobById(id: number, options?: { [key: string]: any }) {
  return request(`/admin/jobs/${id}`, {
    method: 'GET',
    ...(options || {}),
  });
}

/** 创建定时任务 POST /admin/jobs */
export async function createJob(data: API.SysJobCreateRequest, options?: { [key: string]: any }) {
  return request('/admin/jobs', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 更新定时任务 PUT /admin/jobs/update/${jobId} */
export async function updateJob(
  jobId: number,
  data: API.SysJobUpdateRequest,
  options?: { [key: string]: any },
) {
  return request(`/admin/jobs/update/${jobId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
    },
    data: data,
    ...(options || {}),
  });
}

/** 删除定时任务 DELETE /admin/jobs/${jobId} */
export async function deleteJob(jobId: number, options?: { [key: string]: any }) {
  return request(`/admin/jobs/${jobId}`, {
    method: 'DELETE',
    ...(options || {}),
  });
}

/** 启动定时任务 POST /admin/jobs/start/${jobId} */
export async function startJob(jobId: number, options?: { [key: string]: any }) {
  return request(`/admin/jobs/start/${jobId}`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 暂停定时任务 POST /admin/jobs/pause/${jobId} */
export async function pauseJob(jobId: number, options?: { [key: string]: any }) {
  return request(`/admin/jobs/pause/${jobId}`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 立即执行定时任务 POST /admin/jobs/run/${jobId} */
export async function runJobOnce(jobId: number, options?: { [key: string]: any }) {
  return request(`/admin/jobs/run/${jobId}`, {
    method: 'POST',
    ...(options || {}),
  });
}

/** 获取定时任务日志列表 GET /admin/jobs/logs */
export async function getJobLogPage(
  params: {
    pageNum?: number;
    pageSize?: number;
    jobId?: number;
    jobName?: string;
    status?: number;
    startTime?: string;
    endTime?: string;
  },
  options?: { [key: string]: any },
) {
  return request('/admin/jobs/logs', {
    method: 'GET',
    params: {
      ...params,
    },
    ...(options || {}),
  });
}

/** 清理定时任务日志 DELETE /admin/jobs/logs/clean */
export async function cleanJobLogs(days: number, options?: { [key: string]: any }) {
  return request('/admin/jobs/logs/clean', {
    method: 'DELETE',
    params: {
      days,
    },
    ...(options || {}),
  });
}
