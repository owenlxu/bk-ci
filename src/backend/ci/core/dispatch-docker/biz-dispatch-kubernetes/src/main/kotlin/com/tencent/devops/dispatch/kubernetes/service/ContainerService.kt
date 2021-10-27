/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated
 * documentation files (the "Software"), to deal in the Software without restriction, including without limitation the
 * rights to use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to
 * permit persons to whom the Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of
 * the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.dispatch.kubernetes.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.tencent.devops.common.dispatch.sdk.BuildFailureException
import com.tencent.devops.common.dispatch.sdk.pojo.DispatchMessage
import com.tencent.devops.common.log.utils.BuildLogPrinter
import com.tencent.devops.common.pipeline.type.BuildType
import com.tencent.devops.common.redis.RedisOperation
import com.tencent.devops.dispatch.kubernetes.client.ContainerClient
import com.tencent.devops.dispatch.kubernetes.common.ENV_JOB_BUILD_TYPE
import com.tencent.devops.dispatch.kubernetes.common.ENV_KEY_AGENT_ID
import com.tencent.devops.dispatch.kubernetes.common.ENV_KEY_AGENT_SECRET_KEY
import com.tencent.devops.dispatch.kubernetes.common.ENV_KEY_GATEWAY
import com.tencent.devops.dispatch.kubernetes.common.ENV_KEY_PROJECT_ID
import com.tencent.devops.dispatch.kubernetes.common.ErrorCodeEnum
import com.tencent.devops.dispatch.kubernetes.common.SLAVE_ENVIRONMENT
import com.tencent.devops.dispatch.kubernetes.config.DispatchBuildConfig
import com.tencent.devops.dispatch.kubernetes.config.PipelineBuildConfig
import com.tencent.devops.dispatch.kubernetes.dao.BuildContainerPoolNoDao
import com.tencent.devops.dispatch.kubernetes.dao.BuildDao
import com.tencent.devops.dispatch.kubernetes.dao.BuildHisDao
import com.tencent.devops.dispatch.kubernetes.pojo.Action
import com.tencent.devops.dispatch.kubernetes.pojo.BuildContainer
import com.tencent.devops.dispatch.kubernetes.pojo.ContainerStatus
import com.tencent.devops.dispatch.kubernetes.pojo.ContainerType
import com.tencent.devops.dispatch.kubernetes.pojo.IdleContainerInfo
import com.tencent.devops.dispatch.kubernetes.pojo.Life
import com.tencent.devops.dispatch.kubernetes.pojo.Params
import com.tencent.devops.dispatch.kubernetes.pojo.Pool
import com.tencent.devops.dispatch.kubernetes.pojo.Registry
import com.tencent.devops.dispatch.kubernetes.pojo.resp.OperateContainerResult
import com.tencent.devops.dispatch.kubernetes.utils.CommonUtils
import com.tencent.devops.dispatch.kubernetes.utils.PipelineContainerLock
import com.tencent.devops.dispatch.kubernetes.utils.RedisUtils
import com.tencent.devops.process.engine.common.VMUtils
import org.jooq.DSLContext
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Service

@Service
class ContainerService @Autowired constructor(
    private val dslContext: DSLContext,
    private val objectMapper: ObjectMapper,
    private val redisOperation: RedisOperation,
    private val redisUtils: RedisUtils,
    private val dispatchBuildConfig: DispatchBuildConfig,
    private val pipelineBuildConfig: PipelineBuildConfig,
    private val buildLogPrinter: BuildLogPrinter,
    private val containerClient: ContainerClient,
    private val buildDao: BuildDao,
    private val buildHisDao: BuildHisDao,
    private val buildContainerPoolNoDao: BuildContainerPoolNoDao
) {

    companion object {
        private val logger = LoggerFactory.getLogger(ContainerService::class.java)
    }

    fun getIdleContainer(
        dispatchMessage: DispatchMessage,
        cpu: ThreadLocal<Int>,
        memory: ThreadLocal<String>,
        disk: ThreadLocal<String>
    ): IdleContainerInfo {
        val lock = PipelineContainerLock(redisOperation, dispatchMessage.pipelineId, dispatchMessage.vmSeqId)
        try {
            lock.lock()
            for (i in 1..pipelineBuildConfig.buildPoolSize!!) {
                logger.info("poolNo is $i")
                val containerInfo = buildDao.get(dslContext, dispatchMessage.pipelineId, dispatchMessage.vmSeqId, i)
                if (null == containerInfo) {
                    buildDao.createOrUpdate(
                        dslContext = dslContext,
                        pipelineId = dispatchMessage.pipelineId,
                        vmSeqId = dispatchMessage.vmSeqId,
                        poolNo = i,
                        projectId = dispatchMessage.projectId,
                        containerName = "",
                        image = dispatchMessage.dispatchMessage,
                        status = ContainerStatus.BUSY.status,
                        userId = dispatchMessage.userId,
                        cpu = cpu.get(),
                        memory = memory.get(),
                        disk = disk.get()
                    )
                    return IdleContainerInfo(null, i, true)
                } else {
                    if (containerInfo.status == ContainerStatus.BUSY.status) {
                        continue
                    }

                    if (containerInfo.containerName.isEmpty()) {
                        buildDao.createOrUpdate(
                            dslContext = dslContext,
                            pipelineId = dispatchMessage.pipelineId,
                            vmSeqId = dispatchMessage.vmSeqId,
                            poolNo = i,
                            projectId = dispatchMessage.projectId,
                            containerName = "",
                            image = dispatchMessage.dispatchMessage,
                            status = ContainerStatus.BUSY.status,
                            userId = dispatchMessage.userId,
                            cpu = cpu.get(),
                            memory = memory.get(),
                            disk = disk.get()
                        )
                        return IdleContainerInfo(null, i, true)
                    }

                    val statusResponse = containerClient.getContainerStatus(
                        containerName = containerInfo.containerName
                    )

                    if (statusResponse.data?.state?.terminated != null) {
                        var containerChanged = false
                        // 查看构建性能配置是否变更
                        if (cpu.get() != containerInfo.cpu ||
                            disk.get() != containerInfo.disk ||
                            memory.get() != containerInfo.memory
                        ) {
                            containerChanged = true
                            logger.info("buildId: ${dispatchMessage.buildId}, vmSeqId: ${dispatchMessage.vmSeqId} performanceConfig changed.")
                        }

                        // 镜像是否变更
                        if (checkImageChanged(containerInfo.images, dispatchMessage)) {
                            containerChanged = true
                        }

                        buildDao.updateStatus(
                            dslContext = dslContext,
                            pipelineId = dispatchMessage.pipelineId,
                            vmSeqId = dispatchMessage.vmSeqId,
                            poolNo = i,
                            status = ContainerStatus.BUSY.status
                        )
                        return IdleContainerInfo(containerInfo.containerName, i, containerChanged)
                    }
                    // continue to find idle container
                }
            }
            throw BuildFailureException(
                ErrorCodeEnum.NO_IDLE_VM_ERROR.errorType,
                ErrorCodeEnum.NO_IDLE_VM_ERROR.errorCode,
                ErrorCodeEnum.NO_IDLE_VM_ERROR.formatErrorMessage,
                "Dispatch-Kubernetes构建机启动失败，没有空闲的构建机"
            )
        } finally {
            lock.unlock()
        }
    }

    fun createNewContainer(
        dispatchMessage: DispatchMessage,
        containerPool: Pool,
        poolNo: Int,
        cpu: ThreadLocal<Int>,
        memory: ThreadLocal<String>,
        disk: ThreadLocal<String>
    ): OperateContainerResult {
        val (host, name, tag) = CommonUtils.parseImage(containerPool.container!!)
        val userName = containerPool.credential!!.user
        val password = containerPool.credential.password

        with(dispatchMessage) {
            val containerName = containerClient.createContainer(
                this,
                BuildContainer(
                    life = Life.BRIEF,
                    type = ContainerType.DEV,
                    image = "$name:$tag",
                    registry = Registry(host, userName, password),
                    cpu = cpu.get(),
                    memory = memory.get(),
                    disk = disk.get(),
                    replica = 1,
                    ports = emptyList(),
                    params = Params(
                        env = mapOf(
                            ENV_KEY_PROJECT_ID to projectId,
                            ENV_KEY_AGENT_ID to id,
                            ENV_KEY_AGENT_SECRET_KEY to secretKey,
                            ENV_KEY_GATEWAY to gateway,
                            "TERM" to "xterm-256color",
                            SLAVE_ENVIRONMENT to "Kubernetes",
                            ENV_JOB_BUILD_TYPE to (dispatchType?.buildType()?.name ?: BuildType.KUBERNETES.name)
                        ),
                        command = listOf("/bin/sh", dispatchBuildConfig.entrypoint)
                    )
                )
            )
            logger.info(
                "buildId: $buildId,vmSeqId: $vmSeqId,executeCount: $executeCount,poolNo: $poolNo createContainer $containerName"
            )
            printLogs(this, "下发创建构建机请求成功，containerName: $containerName 等待机器启动...")

            // 缓存创建容器信息，防止服务中断或重启引起的信息丢失
            redisUtils.setCreatingContainer(containerName, dispatchMessage.userId)

            val createContainerResult = containerClient.waitContainerStart(containerName)

            // 创建完成移除缓存信息
            redisUtils.removeCreatingContainer(containerName, userId)

            if (createContainerResult.result) {
                // 启动成功
                logger.info(
                    "buildId: $buildId,vmSeqId: $vmSeqId,executeCount: $executeCount,poolNo: $poolNo " +
                        "start deployment success, wait for agent startup..."
                )
                printLogs(this, "构建机启动成功，等待Agent启动...")

                buildDao.createOrUpdate(
                    dslContext = dslContext,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    poolNo = poolNo,
                    projectId = projectId,
                    containerName = containerName,
                    image = this.dispatchMessage,
                    status = ContainerStatus.BUSY.status,
                    userId = userId,
                    cpu = cpu.get(),
                    memory = memory.get(),
                    disk = disk.get()
                )

                // 更新历史表中containerName
                buildHisDao.updateContainerName(dslContext, buildId, vmSeqId, containerName, executeCount ?: 1)

                // 创建成功的要记录，shutdown时关机，创建失败时不记录，shutdown时不关机
                buildContainerPoolNoDao.setBuildLastContainer(
                    dslContext = dslContext, buildId = buildId, vmSeqId = vmSeqId,
                    executeCount = executeCount ?: 1,
                    containerName = containerName, poolNo = poolNo.toString()
                )
                return createContainerResult
            } else {
                // 清除构建异常容器，并重新置构建池为空闲
                clearExceptionContainer(this, containerName)
                buildDao.updateStatus(
                    dslContext = dslContext,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    poolNo = poolNo,
                    status = ContainerStatus.IDLE.status
                )
                buildHisDao.updateContainerName(dslContext, buildId, vmSeqId, containerName, executeCount ?: 1)
                return createContainerResult
            }
        }
    }

    fun startContainer(
        containerName: String,
        dispatchMessage: DispatchMessage,
        poolNo: Int,
        cpu: ThreadLocal<Int>,
        memory: ThreadLocal<String>,
        disk: ThreadLocal<String>
    ): OperateContainerResult {
        with(dispatchMessage) {
            logger.info("buildId: $buildId,vmSeqId: $vmSeqId,executeCount: $executeCount,poolNo: $poolNo start container")

            containerClient.operateContainer(
                containerName = containerName,
                action = Action.START,
                param = Params(
                    env = mapOf(
                        ENV_KEY_PROJECT_ID to projectId,
                        ENV_KEY_AGENT_ID to id,
                        ENV_KEY_AGENT_SECRET_KEY to secretKey,
                        ENV_KEY_GATEWAY to gateway,
                        "TERM" to "xterm-256color",
                        SLAVE_ENVIRONMENT to "Kubernetes",
                        ENV_JOB_BUILD_TYPE to (dispatchType?.buildType()?.name ?: BuildType.KUBERNETES.name)
                    ),
                    command = listOf("/bin/sh", dispatchBuildConfig.entrypoint)
                )
            )

            printLogs(this, "下发启动构建机请求成功，containerName: $containerName 等待机器启动...")
            buildContainerPoolNoDao.setBuildLastContainer(
                dslContext = dslContext,
                buildId = buildId,
                vmSeqId = vmSeqId,
                executeCount = executeCount ?: 1,
                containerName = containerName,
                poolNo = poolNo.toString()
            )

            redisUtils.setStartingContainer(containerName, dispatchMessage.userId)
            val startResult = containerClient.waitContainerStart(containerName)
            redisUtils.removeStartingContainer(containerName, dispatchMessage.userId)

            if (startResult.result) {
                // 启动成功
                logger.info("buildId: $buildId,vmSeqId: $vmSeqId,executeCount: $executeCount,poolNo: $poolNo start dev cloud vm success, wait for agent startup...")
                printLogs(this, "构建机启动成功，等待Agent启动...")

                buildDao.createOrUpdate(
                    dslContext = dslContext,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    poolNo = poolNo,
                    projectId = projectId,
                    containerName = containerName,
                    image = this.dispatchMessage,
                    status = ContainerStatus.BUSY.status,
                    userId = userId,
                    cpu = cpu.get(),
                    memory = memory.get(),
                    disk = disk.get()
                )
                return startResult
            } else {
                // 重置资源池状态
                buildDao.updateStatus(
                    dslContext = dslContext,
                    pipelineId = pipelineId,
                    vmSeqId = vmSeqId,
                    poolNo = poolNo,
                    status = ContainerStatus.IDLE.status
                )
                return startResult
            }
        }
    }

    fun stopContainer(
        buildId: String,
        vmSeqId: String,
        userId: String,
        containerName: String
    ): OperateContainerResult {
        logger.info("buildId: $buildId,vmSeqId: $vmSeqId, $userId stop container")
        containerClient.operateContainer(containerName, Action.STOP, null)
        return containerClient.waitContainerStop(containerName)
    }

    private fun clearExceptionContainer(
        dispatchMessage: DispatchMessage,
        containerName: String
    ) {
        try {
            // 下发删除，不管成功失败
            logger.info(
                "[${dispatchMessage.buildId}]|[${dispatchMessage.vmSeqId}] Delete container, " +
                    "userId: ${dispatchMessage.userId}, containerName: $containerName deploymentName: " +
                    dispatchMessage.buildId
            )
            containerClient.operateContainer(containerName, Action.DELETE, null).let {
                if (!it.result) {
                    logger.error(
                        "[${dispatchMessage.buildId}]|[${dispatchMessage.vmSeqId}] delete container failed",
                        it.errorMessage
                    )
                }
            }
        } catch (e: Exception) {
            logger.error("[${dispatchMessage.buildId}]|[${dispatchMessage.vmSeqId}] delete container failed", e)
        }
    }

    // 镜像是否变更
    private fun checkImageChanged(images: String, dispatchMessage: DispatchMessage): Boolean {
        val containerPool: Pool = objectMapper.readValue(dispatchMessage.dispatchMessage)

        if (containerPool.container != images && dispatchMessage.dispatchMessage != images) {
            logger.info(
                "buildId: ${dispatchMessage.buildId}, " +
                    "vmSeqId: ${dispatchMessage.vmSeqId} image changed. " +
                    "old image: $images, " +
                    "new image: ${dispatchMessage.dispatchMessage}"
            )
            return true
        }
        return false
    }

    private fun printLogs(dispatchMessage: DispatchMessage, message: String) {
        try {
            buildLogPrinter.addLine(
                buildId = dispatchMessage.buildId,
                jobId = dispatchMessage.containerHashId,
                tag = VMUtils.genStartVMTaskId(dispatchMessage.vmSeqId),
                message = message,
                executeCount = dispatchMessage.executeCount ?: 1
            )
        } catch (e: Throwable) {
            // 日志有问题就不打日志了，不能影响正常流程
            logger.error("", e)
        }
    }
}
