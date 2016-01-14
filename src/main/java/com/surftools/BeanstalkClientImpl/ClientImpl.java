package com.surftools.BeanstalkClientImpl;


import com.surftools.BeanstalkClient.BeanstalkException;
import com.surftools.BeanstalkClient.Client;
import com.surftools.BeanstalkClient.Job;
import java.util.List;
import java.util.Map;

public class ClientImpl implements Client
{

    private static final String VERSION = "1.4.6";
    private static final long MAX_PRIORITY = 4294967296L;
    private String host;
    private int port;
    private boolean uniqueConnectionPerThread = true;
    private ProtocolHandler aProtocolHandler = null;
    private ThreadLocal<ProtocolHandler> tlProtocolHandler = new ThreadLocal<ProtocolHandler>()
    {
        @Override
        protected ProtocolHandler initialValue()
        {
            return new ProtocolHandler(host, port);
        }
    };

    private ProtocolHandler getProtocolHandler()
    {
        if(uniqueConnectionPerThread)
        {
            return tlProtocolHandler.get();
        }
        else
        {
            return aProtocolHandler;
        }
    }

    public ClientImpl()
    {
        this(DEFAULT_HOST, DEFAULT_PORT);
    }

    public ClientImpl(String host, int port)
    {
        this.host = host;
        this.port = port;

        aProtocolHandler = new ProtocolHandler(host, port);
    }

    public ClientImpl(boolean useBlockIO)
    {
        this(DEFAULT_HOST, DEFAULT_PORT);
        getProtocolHandler().setUseBlockIO(useBlockIO);
    }

    public ClientImpl(String host, int port, boolean useBlockIO)
    {
        this(host, port);
        getProtocolHandler().setUseBlockIO(useBlockIO);
    }

    // ****************************************************************
    // Producer methods
    // ****************************************************************
    @Override
    public long put(long priority, int delaySeconds, int timeToRun, byte[] data)
    {
        if(data == null)
        {
            throw new BeanstalkException("null data");
        }
        if(priority > MAX_PRIORITY)
        {
            throw new BeanstalkException("invalid priority");
        }
        long jobId = -1;
        Request request = new Request(
                "put " + priority + " " + delaySeconds + " " + timeToRun + " " + data.length,
                new String[]
                {
                    "INSERTED", "BURIED"
                },
                new String[]
                {
                    "JOB_TOO_BIG"
                },
                data,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.getStatus().equals("JOB_TOO_BIG"))
        {
            BeanstalkException be = new BeanstalkException(response.getStatus());
            throw be;
        }
        if(response != null && response.isMatchOk())
        {
            jobId = Long.parseLong(response.getReponse());
        }
        return jobId;
    }

    @Override
    public void useTube(String tubeName)
    {
        if(tubeName == null)
        {
            throw new BeanstalkException("null tubeName");
        }
        Request request = new Request(
                "use " + tubeName,
                "USING",
                null,
                null,
                ExpectedResponse.None);
        getProtocolHandler().processRequest(request);
    }

    // ****************************************************************
    // Consumer methods
    //	job-related
    // ****************************************************************	
    @Override
    public Job reserve(Integer timeoutSeconds)
    {
        Job job = null;
        String command = (timeoutSeconds == null)
                         ? "reserve"
                         : "reserve-with-timeout " + timeoutSeconds.toString();
        Request request = new Request(
                command,
                new String[]
                {
                    "RESERVED"
                },
                new String[]
                {
                    "DEADLINE_SOON", "TIMED_OUT",
                },
                null,
                ExpectedResponse.ByteArray,
                2);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.getStatus().equals("DEADLINE_SOON"))
        {
            BeanstalkException be = new BeanstalkException(response.getStatus());
            throw be;
        }
        if(response != null && response.isMatchOk())
        {
            long jobId = Long.parseLong(response.getReponse());
            job = new JobImpl(jobId);
            job.setData((byte[]) response.getData());
        }
        return job;
    }

    @Override
    public boolean delete(long jobId)
    {
        Request request = new Request(
                "delete " + jobId,
                "DELETED",
                "NOT_FOUND",
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return response != null && response.isMatchOk();
    }

    @Override
    public boolean release(long jobId, long priority, int delaySeconds)
    {
        Request request = new Request(
                "release " + jobId + " " + priority + " " + delaySeconds,
                new String[]
                {
                    "RELEASED"
                },
                new String[]
                {
                    "NOT_FOUND", "BURIED"
                },
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return response != null && response.isMatchOk();
    }

    @Override
    public boolean bury(long jobId, long priority)
    {
        Request request = new Request(
                "bury " + jobId + " " + priority,
                "BURIED",
                "NOT_FOUND",
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return response != null && response.isMatchOk();
    }

    @Override
    public boolean touch(long jobId)
    {
        Request request = new Request(
                "touch " + jobId,
                "TOUCHED",
                "NOT_FOUND",
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return response != null && response.isMatchOk();
    }

    // ****************************************************************
    // Consumer methods
    //	tube-related
    // ****************************************************************
    @Override
    public int watch(String tubeName)
    {
        if(tubeName == null)
        {
            throw new BeanstalkException("null tubeName");
        }
        Request request = new Request(
                "watch " + tubeName,
                "WATCHING",
                null,
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return Integer.parseInt(response.getReponse());
    }

    @Override
    public int ignore(String tubeName)
    {
        if(tubeName == null)
        {
            throw new BeanstalkException("null tubeName");
        }
        Request request = new Request(
                "ignore " + tubeName,
                new String[]
                {
                    "WATCHING", "NOT_IGNORED"
                },
                null,
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        return (response.getReponse() == null) ? -1 : Integer.parseInt(response.getReponse());
    }

    // ****************************************************************
    // Consumer methods
    //	peek-related
    // ****************************************************************
    @Override
    public Job peek(long jobId)
    {
        Job job = null;
        Request request = new Request(
                "peek " + jobId,
                "FOUND",
                "NOT_FOUND",
                null,
                ExpectedResponse.ByteArray,
                2);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            jobId = Long.parseLong(response.getReponse());
            job = new JobImpl(jobId);
            job.setData((byte[]) response.getData());
        }
        return job;
    }

    @Override
    public Job peekBuried()
    {
        Job job = null;
        Request request = new Request(
                "peek-buried",
                "FOUND",
                "NOT_FOUND",
                null,
                ExpectedResponse.ByteArray,
                2);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            long jobId = Long.parseLong(response.getReponse());
            job = new JobImpl(jobId);
            job.setData((byte[]) response.getData());
        }
        return job;
    }

    @Override
    public Job peekDelayed()
    {
        Job job = null;
        Request request = new Request(
                "peek-delayed",
                "FOUND",
                "NOT_FOUND",
                null,
                ExpectedResponse.ByteArray,
                2);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            long jobId = Long.parseLong(response.getReponse());
            job = new JobImpl(jobId);
            job.setData((byte[]) response.getData());
        }
        return job;
    }

    @Override
    public Job peekReady()
    {
        Job job = null;
        Request request = new Request(
                "peek-ready",
                "FOUND",
                "NOT_FOUND",
                null,
                ExpectedResponse.ByteArray,
                2);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            long jobId = Long.parseLong(response.getReponse());
            job = new JobImpl(jobId);
            job.setData((byte[]) response.getData());
        }
        return job;
    }

    @Override
    public int kick(int count)
    {
        Request request = new Request(
                "kick " + count,
                "KICKED",
                null,
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            count = Integer.parseInt(response.getReponse());
        }
        return count;
    }

    // ****************************************************************
    // Consumer methods
    //	stats-related
    // ****************************************************************
    @SuppressWarnings("unchecked")
    @Override
    public Map<String, String> statsJob(long jobId)
    {
        Request request = new Request(
                "stats-job " + jobId,
                "OK",
                "NOT_FOUND",
                null,
                ExpectedResponse.Map);
        Response response = getProtocolHandler().processRequest(request);
        Map<String, String> map = null;
        if(response != null && response.isMatchOk())
        {
            map = (Map<String, String>) response.getData();
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, String> statsTube(String tubeName)
    {
        if(tubeName == null)
        {
            return null;
        }

        Request request = new Request(
                "stats-tube " + tubeName,
                "OK",
                "NOT_FOUND",
                null,
                ExpectedResponse.Map);
        Response response = getProtocolHandler().processRequest(request);
        Map<String, String> map = null;
        if(response != null && response.isMatchOk())
        {
            map = (Map<String, String>) response.getData();
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Map<String, String> stats()
    {
        Request request = new Request(
                "stats",
                "OK",
                null,
                null,
                ExpectedResponse.Map);
        Response response = getProtocolHandler().processRequest(request);
        Map<String, String> map = null;
        if(response != null && response.isMatchOk())
        {
            map = (Map<String, String>) response.getData();
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> listTubes()
    {
        Request request = new Request(
                "list-tubes",
                "OK",
                null,
                null,
                ExpectedResponse.List);
        Response response = getProtocolHandler().processRequest(request);
        List<String> list = null;
        if(response != null && response.isMatchOk())
        {
            list = (List<String>) response.getData();
        }
        return list;
    }

    @Override
    public String listTubeUsed()
    {
        String tubeName = null;
        Request request = new Request(
                "list-tube-used",
                "USING",
                null,
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            tubeName = response.getReponse();
        }
        return tubeName;
    }

    @SuppressWarnings("unchecked")
    @Override
    public List<String> listTubesWatched()
    {
        Request request = new Request(
                "list-tubes-watched",
                "OK",
                null,
                null,
                ExpectedResponse.List);
        Response response = getProtocolHandler().processRequest(request);
        List<String> list = null;
        if(response != null && response.isMatchOk())
        {
            list = (List<String>) response.getData();
        }
        return list;
    }

    @Override
    public String getClientVersion()
    {
        return VERSION;
    }

    @Override
    public void close()
    {
        getProtocolHandler().close();
    }

    @Override
    public boolean isUniqueConnectionPerThread()
    {
        return uniqueConnectionPerThread;
    }

    @Override
    public void setUniqueConnectionPerThread(boolean uniqueConnectionPerThread)
    {
        this.uniqueConnectionPerThread = uniqueConnectionPerThread;
    }

    @Override
    public boolean pauseTube(String tubeName, int pauseDelay)
    {
        Request request = new Request(
                "pause-tube " + tubeName + " " + pauseDelay,
                "PAUSED",
                null,
                null,
                ExpectedResponse.None);
        Response response = getProtocolHandler().processRequest(request);
        if(response != null && response.isMatchOk())
        {
            return true;
        }
        return false;
    }

    @Override
    public String getServerVersion()
    {
        Map<String, String> stats = stats();
        if(stats == null)
        {
            throw new BeanstalkException("could not get stats");
        }
        return stats.get("version").trim();
    }
}
