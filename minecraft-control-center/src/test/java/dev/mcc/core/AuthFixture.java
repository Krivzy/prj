package dev.mcc.core;
import java.util.*;
final class AuthFixture {
    static Auth.Session owner(Auth auth,String name,String password)throws Exception{return auth.completeSetup(UUID.randomUUID(),name,password,LicenseService.stored("temporary-full","test-fixture",System.currentTimeMillis()),Map.of("completed",true));}
}
