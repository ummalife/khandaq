// FIXTURE — must trip khandaq-java-runtime-exec-nonliteral and khandaq-java-random-fills-buffer.
// Never compiled into any source set, never shipped.
package security.sastfixtures;

public class FixtureJavaExec
{
    public void run(String tainted) throws Exception
    {
        Runtime.getRuntime().exec("sh -c " + tainted);
    }

    public byte[] mintCapability()
    {
        byte[] out = new byte[32];
        new java.util.Random().nextBytes(out);
        return out;
    }
}
