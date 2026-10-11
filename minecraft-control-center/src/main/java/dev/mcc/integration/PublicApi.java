package dev.mcc.integration;

import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;

/** Calls only public API contracts; never opens private implementation members. */
final class PublicApi {
    private PublicApi() {}
    static Class<?> type(ClassLoader loader,String name)throws ClassNotFoundException{return Class.forName(name,false,loader);}
    static Object call(Object target,String name,Object...args)throws Exception {
        Class<?> type=target instanceof Class<?> c?c:target.getClass();
        Method method=target instanceof Class<?>?Arrays.stream(type.getMethods()).filter(m->Modifier.isStatic(m.getModifiers())&&Modifier.isPublic(m.getDeclaringClass().getModifiers())&&m.getName().equals(name)&&matches(m.getParameterTypes(),args)).findFirst().orElse(null):find(type,name,args,new HashSet<>());
        if(method==null)throw new UnsupportedOperationException("Unsupported public API: "+name);
        try{return method.invoke(target instanceof Class<?>?null:target,args);}
        catch(InvocationTargetException e){Throwable cause=e.getCause();if(cause instanceof Exception x)throw x;if(cause instanceof Error x)throw x;throw e;}
    }
    private static Method find(Class<?> type,String name,Object[] args,Set<Class<?>> seen) {
        if(type==null||!seen.add(type))return null;
        // A non-public implementation may implement a public interface. Invoke that interface.
        for(Class<?> contract:type.getInterfaces()){Method found=find(contract,name,args,seen);if(found!=null)return found;}
        if(Modifier.isPublic(type.getModifiers()))for(Method m:type.getMethods())if(!Modifier.isStatic(m.getModifiers())&&Modifier.isPublic(m.getDeclaringClass().getModifiers())&&m.getName().equals(name)&&matches(m.getParameterTypes(),args))return m;
        return find(type.getSuperclass(),name,args,seen);
    }
    private static boolean matches(Class<?>[] parameters,Object[] args) {
        if(parameters.length!=args.length)return false;
        for(int i=0;i<args.length;i++)if(args[i]==null?parameters[i].isPrimitive():!boxed(parameters[i]).isInstance(args[i]))return false;
        return true;
    }
    private static Class<?> boxed(Class<?> c){if(!c.isPrimitive())return c;return c==boolean.class?Boolean.class:c==int.class?Integer.class:c==long.class?Long.class:c==double.class?Double.class:c==float.class?Float.class:c==byte.class?Byte.class:c==short.class?Short.class:Character.class;}
    static boolean has(Object target,String name,int count){Class<?> type=target instanceof Class<?> c?c:target.getClass();return Arrays.stream(type.getMethods()).anyMatch(m->m.getName().equals(name)&&m.getParameterCount()==count&&Modifier.isPublic(m.getModifiers()));}
    static Object readFuture(Object value)throws Exception {
        if(!(value instanceof CompletableFuture<?> future))throw new UnsupportedOperationException("API did not return an asynchronous result");
        return future.get(); // Off-main. Retain the worker reservation until the provider actually finishes.
    }
    static void saveFuture(Object value)throws Exception {
        if(!(value instanceof CompletableFuture<?> future))throw new UnsupportedOperationException("API did not return a save receipt");
        future.get(); // A started save remains tracked; a timeout must not claim the write failed.
    }
    static Collection<?> collection(Object value){if(!(value instanceof Collection<?> c))throw new UnsupportedOperationException("Unexpected collection in plugin API");return c;}
    static String text(Object value,int max){String s=value==null?"":String.valueOf(value);return s.length()>max?s.substring(0,max):s;}
}
