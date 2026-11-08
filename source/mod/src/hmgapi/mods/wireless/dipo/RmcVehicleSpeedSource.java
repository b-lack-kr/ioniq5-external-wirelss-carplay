package hmgapi.mods.wireless.dipo;

/** Actual callback observations, scoped to the phone request and CP generation.
 * A long unchanged-speed age is not proof of stale data; only lifecycle/invalid
 * callbacks invalidate it. Never reads a field whose cold-start default is0. */
final class RmcVehicleSpeedSource {
    static final class Request {
        final Object manager;Object session;boolean ready;int kmh=-1;long at;
        Request(Object manager,Object session){this.manager=manager;this.session=session;}
    }
    static final class Sample {
        final Request request;final int kmh;final long age;
        Sample(Request r,long now){request=r;kmh=r.kmh;age=Math.max(0,now-r.at);}
    }
    private Request current;
    synchronized Request begin(Object manager,Object session){current=new Request(manager,session);return current;}
    synchronized void complete(Request request,boolean success){if(current==request){if(success)request.ready=true;else current=null;}}
    synchronized void stop(Object manager){if(current!=null&&current.manager==manager)current=null;}
    synchronized void reset(){current=null;}
    synchronized void invalidateSample(){if(current!=null)current.kmh=-1;}
    synchronized void changed(Object manager,Object session,int value,long now){
        if(current==null||current.manager!=manager)return;
        if(session==null){current.kmh=-1;return;}
        if(current.session==null)current.session=session;
        if(current.session!=session){current.kmh=-1;return;}
        current.kmh=value>=0&&value<511?value:-1;current.at=now;
    }
    synchronized Sample sample(Object manager,Object session,long now){
        return current!=null&&current.ready&&current.manager==manager&&session!=null&&current.session==session&&current.kmh>=0
                ?new Sample(current,now):null;
    }
    synchronized boolean current(Sample sample,Object manager,Object session){
        return sample!=null&&current==sample.request&&current.ready&&current.manager==manager&&current.session==session
                &&current.kmh==sample.kmh;
    }
}
