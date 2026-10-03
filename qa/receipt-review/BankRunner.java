package com.pixelclub.teams.money;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Runs the repository's unedited JUnit assertion bodies with fresh H2/fake-provider fixtures. */
public final class BankRunner {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]);Files.createDirectories(root);int passed=0,failed=0;
        var tests=Arrays.stream(BankServiceTest.class.getDeclaredMethods()).filter(x->x.isAnnotationPresent(Test.class)).sorted(Comparator.comparing(Method::getName)).toList();
        for(Method method:tests) {
            BankServiceTest fixture=new BankServiceTest();fixture.temporary=Files.createTempDirectory(root,"bank-");
            try {fixture.setup();method.invoke(fixture);passed++;}
            catch(InvocationTargetException e){failed++;System.out.println("FAIL "+method.getName()+": "+e.getCause());}
        }
        System.out.println("BankService actual-source JUnit bodies: passed="+passed+" failed="+failed+" total="+tests.size());
        if(failed>0)throw new AssertionError("Bank assertion failures");
    }
}
