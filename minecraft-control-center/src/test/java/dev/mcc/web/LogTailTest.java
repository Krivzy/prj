package dev.mcc.web;

import dev.mcc.core.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class LogTailTest {
    @TempDir Path directory;
    private static final class Capture {
        final List<List<Map<String,Object>>> batches=new ArrayList<>();
        final LogTail tail;
        Capture(Path file){tail=new LogTail(file,batches::add);}
        void pulse(){
            int before=batches.size();tail.pulse();assertTrue(batches.size()-before<=4);
            for(List<Map<String,Object>> batch:batches.subList(before,batches.size())){
                assertFalse(batch.isEmpty());assertTrue(batch.size()<=16);
                assertTrue(Json.write(batch).getBytes(StandardCharsets.UTF_8).length<=24576);
                assertTrue(Json.write(Map.of("type","event","topic","console","sequence",Long.MAX_VALUE,"data",batch)).getBytes(StandardCharsets.UTF_8).length<32768);
                assertThrows(UnsupportedOperationException.class,()->batch.add(Map.of()));
            }
        }
        List<String> messages(){return batches.stream().flatMap(List::stream).map(row->(String)row.get("message")).toList();}
        void drain(int rows){for(int i=0;i<100&&messages().size()<rows;i++)pulse();assertEquals(rows,messages().size());}
    }
    private Path empty(String name)throws Exception{Path file=directory.resolve(name);Files.write(file,new byte[0]);return file;}
    private static void append(Path file,String text)throws Exception{Files.writeString(file,text,StandardCharsets.UTF_8,StandardOpenOption.APPEND);}
    private static List<String> numbered(int count){List<String> rows=new ArrayList<>();for(int i=0;i<count;i++)rows.add("line-"+i);return rows;}

    @Test void startupBacklogOf151LinesIsPacedWithoutLostOrDuplicateRows()throws Exception{
        List<String> expected=numbered(151);Path file=directory.resolve("startup.log");Files.writeString(file,String.join("\n",expected)+"\n");
        Capture capture=new Capture(file);capture.pulse();assertEquals(64,capture.messages().size());assertEquals(4,capture.batches.size());
        capture.drain(151);assertEquals(expected,capture.messages());assertEquals(expected,capture.tail.history().stream().map(row->(String)row.get("message")).toList());
        int frames=capture.batches.size();capture.pulse();capture.pulse();assertEquals(frames,capture.batches.size());
    }

    @Test void utf8AndJsonEscapeBudgetsIncludeOversizedSingleRowsAndValidSurrogates()throws Exception{
        Path file=empty("escaped.log");Capture capture=new Capture(file);capture.pulse();
        append(file,"\u0000".repeat(4096)+"\n"+"\u0001".repeat(4096)+"\n"+"漢".repeat(4096)+"\n"+"😃".repeat(4096)+"\n"+"x".repeat(8000)+"\n");
        capture.drain(5);assertEquals(5,capture.messages().size());
        for(String message:capture.messages()){
            assertTrue(message.length()<=4096);assertFalse(message.contains("\uFFFD"));
            assertTrue(Json.write(message).getBytes(StandardCharsets.UTF_8).length<=16384);
            for(int i=0;i<message.length();i++){
                char value=message.charAt(i);
                if(Character.isHighSurrogate(value)){assertTrue(i+1<message.length());assertTrue(Character.isLowSurrogate(message.charAt(++i)));}
                else assertFalse(Character.isLowSurrogate(value));
            }
        }
        assertTrue(capture.messages().getFirst().endsWith("…"));assertTrue(capture.messages().get(3).endsWith("…"));assertTrue(capture.messages().get(4).endsWith("…"));
    }

    @Test void incompleteUtf8CodePointAndCrLfSurviveSeparateFileReads()throws Exception{
        Path file=empty("utf8.log");Capture capture=new Capture(file);capture.pulse();byte[] emoji="😃".getBytes(StandardCharsets.UTF_8);
        append(file,"before-");Files.write(file,Arrays.copyOf(emoji,2),StandardOpenOption.APPEND);capture.pulse();assertTrue(capture.messages().isEmpty());
        Files.write(file,Arrays.copyOfRange(emoji,2,4),StandardOpenOption.APPEND);append(file,"-after\r");capture.pulse();assertTrue(capture.messages().isEmpty());
        append(file,"\nnext\n");capture.pulse();assertEquals(List.of("before-😃-after","next"),capture.messages());
    }

    @Test void unfinishedLineIsNotPublishedAndSecretRedactionRunsAfterItsCompletion()throws Exception{
        Path file=empty("secret.log");Capture capture=new Capture(file);capture.pulse();
        append(file,"WARN password=synthetic-first");capture.pulse();assertTrue(capture.messages().isEmpty());
        append(file,"-continued token=synthetic-second authorization:synthetic-third\r\nERROR done\n");capture.pulse();
        assertEquals(List.of("WARN password=[redacted] token=[redacted] authorization=[redacted]","ERROR done"),capture.messages());
        assertEquals(List.of("WARN","ERROR"),capture.tail.history().stream().map(row->row.get("level")).toList());
        assertFalse(Json.write(capture.tail.history()).contains("synthetic-"));
    }

    @Test void pendingBudgetRowsStayBeforeLaterAppendedRowsAndAnUnfinishedLine()throws Exception{
        Path file=empty("append.log");Capture capture=new Capture(file);capture.pulse();List<String> expected=numbered(151);
        append(file,String.join("\n",expected)+"\nunfinished");capture.pulse();append(file,"-completed\nlater\n");
        capture.drain(153);List<String> actual=new ArrayList<>(expected);actual.add("unfinished-completed");actual.add("later");assertEquals(actual,capture.messages());
    }

    @Test void last500HistoryRowsAreRetainedAfterAll600RowsWereDelivered()throws Exception{
        Path file=empty("history.log");Capture capture=new Capture(file);capture.pulse();List<String> expected=numbered(600);
        append(file,String.join("\n",expected)+"\n");capture.drain(600);assertEquals(expected,capture.messages());
        assertEquals(expected.subList(100,600),capture.tail.history().stream().map(row->(String)row.get("message")).toList());
    }

    @Test void fileTruncationResetsAnUnfinishedOldLine()throws Exception{
        Path file=empty("truncate.log");Capture capture=new Capture(file);capture.pulse();append(file,"old-unfinished-line");capture.pulse();
        Files.writeString(file,"new\n",StandardCharsets.UTF_8,StandardOpenOption.TRUNCATE_EXISTING);capture.pulse();assertEquals(List.of("new"),capture.messages());
    }

    @Test void retainedCompleteRowsDrainBeforeTheReplacementFile()throws Exception{
        Path file=empty("replace.log");Capture capture=new Capture(file);capture.pulse();List<String> expected=numbered(151);
        append(file,String.join("\n",expected)+"\nold-unfinished");capture.pulse();
        Files.move(file,directory.resolve("replace.old"));Files.writeString(file,"replacement\n");capture.drain(152);
        List<String> actual=new ArrayList<>(expected);actual.add("replacement");assertEquals(actual,capture.messages());
    }

    @Test void initialAndCatchupTailsSkipOnlyTheCutPhysicalLine()throws Exception{
        Path initial=directory.resolve("initial.log");Files.writeString(initial,"😃".repeat(5000)+"\nkept-one\nkept-two\n");Capture first=new Capture(initial);first.pulse();assertEquals(List.of("kept-one","kept-two"),first.messages());
        Path grown=empty("grown.log");Capture later=new Capture(grown);later.pulse();append(grown,"x".repeat(1_000_000)+"\nlast-one\nlast-two\n");later.pulse();assertEquals(List.of("last-one","last-two"),later.messages());
    }

    @Test void aVeryLongUnfinishedLineUsesOneTruncatedRowOnlyAfterLf()throws Exception{
        Path file=empty("long.log");Capture capture=new Capture(file);capture.pulse();
        for(int i=0;i<12;i++){append(file,"x".repeat(4096));capture.pulse();assertTrue(capture.messages().isEmpty());}
        append(file,"\nfollowing\n");capture.pulse();assertEquals(List.of("x".repeat(4095)+"…","following"),capture.messages());
    }

    @Test void alreadyReadCompleteRowsRemainAvailableDuringATemporaryFileAbsence()throws Exception{
        Path file=empty("absent.log");Capture capture=new Capture(file);capture.pulse();List<String> expected=numbered(151);
        append(file,String.join("\n",expected)+"\n");capture.pulse();Files.delete(file);capture.drain(151);assertEquals(expected,capture.messages());
    }
}
