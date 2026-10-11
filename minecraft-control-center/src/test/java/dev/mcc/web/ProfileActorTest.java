package dev.mcc.web;
import dev.mcc.core.Auth;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ProfileActorTest {
    @Test void reusedAccountNameCannotReuseAnotherSessionPlan(){var first=new Auth.Session("session-first","admin","Owner","csrf-one",1,false);var second=new Auth.Session("session-second","admin","Owner","csrf-two",1,false);assertNotEquals(WebServer.profileActor(first),WebServer.profileActor(second));assertEquals(WebServer.profileActor(first),WebServer.profileActor(first));}
    @Test void persistedPlanActorContainsNoAuthenticationCredential(){var session=new Auth.Session("private-session-token","admin","Owner","private-csrf-token",1,false);String actor=WebServer.profileActor(session);assertTrue(actor.matches("[a-f0-9]{64}"));assertFalse(actor.contains(session.id()));assertFalse(actor.contains(session.csrf()));}
}
