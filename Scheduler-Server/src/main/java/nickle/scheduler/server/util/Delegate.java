package nickle.scheduler.server.util;

import akka.actor.ActorContext;
import akka.actor.ActorSelection;
import akka.dispatch.OnComplete;
import akka.pattern.Patterns;
import akka.util.Timeout;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import lombok.extern.slf4j.Slf4j;
import nickle.scheduler.common.event.ExecuteJobEvent;
import nickle.scheduler.server.entity.*;
import nickle.scheduler.server.mapper.NickleSchedulerExecutorJobMapper;
import nickle.scheduler.server.mapper.NickleSchedulerExecutorMapper;
import nickle.scheduler.server.mapper.NickleSchedulerFailJobMapper;
import nickle.scheduler.server.mapper.NickleSchedulerRunJobMapper;
import org.apache.ibatis.session.SqlSession;
import org.springframework.util.CollectionUtils;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.Collection;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static nickle.scheduler.common.Constant.*;
import static nickle.scheduler.server.constant.Constant.NO_EXECUTOR;
import static nickle.scheduler.server.constant.Constant.NO_EXECUTOR_FAIL;

/**
 * @author nickle
 * @description:
 * @date 2019/5/8 10:17
 */
@Slf4j
public class Delegate {
    public static <T> void insertBatch(Collection<T> entityList, SqlSession sqlSession) {

    }

    public static void scheduleJob(List<NickleSchedulerJob> nickleSchedulerRunJobs) {
        SqlSession sqlSession = ThreadLocals.getSqlSession();
        NickleSchedulerExecutorJobMapper executorJobMapper = sqlSession.getMapper(NickleSchedulerExecutorJobMapper.class);
        NickleSchedulerFailJobMapper failJobMapper = sqlSession.getMapper(NickleSchedulerFailJobMapper.class);
        log.info("需要调度的job：{}", nickleSchedulerRunJobs);
        for (NickleSchedulerJob job : nickleSchedulerRunJobs) {
            //获取到job对应执行器，选取发送
            QueryWrapper<NickleSchedulerExecutorJob> executorJobQueryWrapper = new QueryWrapper<>();
            executorJobQueryWrapper.lambda().eq(NickleSchedulerExecutorJob::getJobName, job.getJobName());
            List<NickleSchedulerExecutorJob> nickleSchedulerExecutorJobs = executorJobMapper.selectList(executorJobQueryWrapper);
            if (CollectionUtils.isEmpty(nickleSchedulerExecutorJobs)) {
                //此时该job的执行器为空，待处理
                log.error("当前任务没有可用执行器:{},已记录到失败表中", job);
                //记录到失败表中
                NickleSchedulerFailJob nickleSchedulerFailJob = new NickleSchedulerFailJob();
                //如果第一次调度时保存将没有执行器
                nickleSchedulerFailJob.setExecutorId(NO_EXECUTOR);
                nickleSchedulerFailJob.setJobName(job.getJobName());
                nickleSchedulerFailJob.setFailReason(NO_EXECUTOR_FAIL);
                nickleSchedulerFailJob.setTriggerName(job.getJobTriggerName());
                nickleSchedulerFailJob.setFailedTime(System.currentTimeMillis());
                nickleSchedulerFailJob.setJobId(job.getId());
                failJobMapper.insert(nickleSchedulerFailJob);
                continue;
            } else {
                //目前仅为简单任务
                scheduleSimpleJob(job, nickleSchedulerExecutorJobs);
            }
        }
    }

    /**
     * 调取简单任务
     *
     * @param job
     * @param nickleSchedulerExecutorJobs
     */
    public static void scheduleSimpleJob(NickleSchedulerJob job, List<NickleSchedulerExecutorJob> nickleSchedulerExecutorJobs) {
        ActorContext actorContext = ThreadLocals.getActorContext();
        SqlSession sqlSession = ThreadLocals.getSqlSession();
        NickleSchedulerExecutorMapper executorMapper = sqlSession.getMapper(NickleSchedulerExecutorMapper.class);
        //简单任务目前采用轮询算法来保证任务高可用
        for (NickleSchedulerExecutorJob executorJob : nickleSchedulerExecutorJobs) {
            //获取到执行器ip和端口
            QueryWrapper<NickleSchedulerExecutor> schedulerExecutorQueryWrapper = new QueryWrapper<>();
            schedulerExecutorQueryWrapper.lambda().eq(NickleSchedulerExecutor::getExecutorName, executorJob.getExecutorName());
            NickleSchedulerExecutor nickleSchedulerExecutor = executorMapper.selectOne(schedulerExecutorQueryWrapper);
            if (nickleSchedulerExecutor == null) {
                log.error("关联表有执行器，但是执行器表没有对应执行器数据");
                continue;
            }
            //拼接获取远程actor发送信息
            String executorDispatcherPath = String.format(AKKA_REMOTE_MODEL
                    , EXECUTOR_SYSTEM_NAME
                    , nickleSchedulerExecutor.getExecutorIp()
                    , nickleSchedulerExecutor.getExecutorPort()
                    , EXECUTOR_DISPATCHER_NAME);
            ActorSelection actorSelection = actorContext.actorSelection(executorDispatcherPath);
            ExecuteJobEvent executorStartEvent = new ExecuteJobEvent();
            executorStartEvent.setClassName(job.getJobClassName());
            log.info("开始调度简单job：{},目标主机路径：{}", job, executorDispatcherPath);
            //需确保DispacherActor真实收到任务所以采用ask模式,超时时间设置1s
            Timeout t = new Timeout(Duration.create(1, TimeUnit.SECONDS));
            CountDownLatch countDownLatch = new CountDownLatch(1);
            Future<Object> ask = Patterns.ask(actorSelection, executorStartEvent, t);
            boolean[] registerSuccess = new boolean[1];
            registerSuccess[0] = false;
            ask.onComplete(new OnComplete<Object>() {
                @Override
                public void onComplete(Throwable throwable, Object o) throws Throwable {
                    if (throwable != null) {
                        log.error("执行器:{}连接超时,即将删除", nickleSchedulerExecutor);
                        //删除执行器
                        deleteExecutor(sqlSession, nickleSchedulerExecutor);
                        log.error("执行器:{}删除成功", nickleSchedulerExecutor);
                    } else {
                        registerSuccess[0] = true;
                    }
                    countDownLatch.countDown();
                }
            }, actorContext.dispatcher());
            //等待任务注册完毕
            try {
                countDownLatch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (registerSuccess[0]) {
                log.info("job:{}调度成功", job);
                insertRunJob(sqlSession, nickleSchedulerExecutor.getExecutorId(), job);
                return;
            }
        }

    }

    /**
     * 删除执行器
     *
     * @param sqlSession
     * @param nickleSchedulerExecutors
     */
    public static void deleteExecutor(SqlSession sqlSession, NickleSchedulerExecutor... nickleSchedulerExecutors) {
        List<String> nameList = Stream.of(nickleSchedulerExecutors).map(nickleSchedulerExecutor ->
                String.format(SOCKET_ADDRESS_MODEL, nickleSchedulerExecutor.getExecutorIp(), nickleSchedulerExecutor.getExecutorPort())).collect(Collectors.toList());
        NickleSchedulerExecutorMapper executorMapper = sqlSession.getMapper(NickleSchedulerExecutorMapper.class);
        NickleSchedulerExecutorJobMapper executorJobMapper = sqlSession.getMapper(NickleSchedulerExecutorJobMapper.class);
        QueryWrapper<NickleSchedulerExecutor> executorQueryWrapper = new QueryWrapper<>();
        executorQueryWrapper.lambda().in(NickleSchedulerExecutor::getExecutorName, nameList);
        executorMapper.delete(executorQueryWrapper);
        //级联删除关联表
        QueryWrapper<NickleSchedulerExecutorJob> queryWrapper = new QueryWrapper<>();
        queryWrapper.lambda().in(NickleSchedulerExecutorJob::getExecutorName, nameList);
        executorJobMapper.delete(queryWrapper);
    }

    /**
     * 将任务插入到正在运行表
     *
     * @param sqlSession
     * @param executorId
     * @param job
     */
    public static void insertRunJob(SqlSession sqlSession, Integer executorId, NickleSchedulerJob job) {
        //插入运行队列
        NickleSchedulerRunJobMapper runJobMapper = sqlSession.getMapper(NickleSchedulerRunJobMapper.class);
        NickleSchedulerRunJob nickleSchedulerRunJob = new NickleSchedulerRunJob();
        nickleSchedulerRunJob.setExecutorId(executorId);
        nickleSchedulerRunJob.setJobName(job.getJobName());
        nickleSchedulerRunJob.setJobId(job.getId());
        nickleSchedulerRunJob.setScheduleTime(System.currentTimeMillis());
        nickleSchedulerRunJob.setTriggerName(job.getJobTriggerName());
        nickleSchedulerRunJob.setUpdateTime(System.currentTimeMillis());
        runJobMapper.insert(nickleSchedulerRunJob);
    }

    /**
     * 插入主机
     *
     * @param sqlSession
     * @param ip
     * @param port
     */
    public static void insertExecutor(SqlSession sqlSession, String ip, Integer port) {
        NickleSchedulerExecutorMapper executorMapper = sqlSession.getMapper(NickleSchedulerExecutorMapper.class);
        String ipPortStr = String.format(SOCKET_ADDRESS_MODEL, ip, port);
        NickleSchedulerExecutor nickleSchedulerExecutor = new NickleSchedulerExecutor();
        nickleSchedulerExecutor.setUpdateTime(System.currentTimeMillis());
        nickleSchedulerExecutor.setExecutorPort(port);
        nickleSchedulerExecutor.setExecutorIp(ip);
        nickleSchedulerExecutor.setExecutorName(ipPortStr);
        log.info("插入主机");
        executorMapper.insert(nickleSchedulerExecutor);
    }

    /**
     * 插入主机任务关联表
     *
     * @param sqlSession
     * @param ip
     * @param port
     * @param jobName
     */
    public static void insertExecutorJob(SqlSession sqlSession, String ip, Integer port, String jobName) {
        NickleSchedulerExecutorJobMapper executorJobMapper = sqlSession.getMapper(NickleSchedulerExecutorJobMapper.class);
        String ipPortStr = String.format(SOCKET_ADDRESS_MODEL, ip, port);
        NickleSchedulerExecutorJob executorJob = new NickleSchedulerExecutorJob();
        executorJob.setExecutorName(ipPortStr);
        executorJob.setJobName(jobName);
        executorJobMapper.insert(executorJob);
        log.info("插入主机 JOB关联表");
        executorJobMapper.insert(executorJob);
    }
}
