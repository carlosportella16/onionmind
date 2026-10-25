#!/usr/bin/env bash
# Measures the CPU and memory cost of local ONNX embeddings (multilingual-e5-small, 384 dims,
# SentencePiece tokenizer, one chunk per call) on this host, to size the VPS for in-process
# embeddings in the core. Needs only Docker and internet access to Maven Central and Hugging
# Face. Nothing leaves the host: by default it embeds synthetic text.
#
#   bash scripts/embedding-bench.sh                  # int8 model, synthetic 2048-char chunks
#   FP32=1 bash scripts/embedding-bench.sh           # also fp32 (+470 MB download) and int8-vs-fp32 similarity
#   CHUNKS_FILE=chunks.txt bash scripts/embedding-bench.sh   # one chunk per line, e.g. from the pages table
#
# Results are printed and appended to $WORK/results.txt.
set -euo pipefail

WORK=${WORK:-$HOME/.cache/onionmind-embedding-bench}
N=${N:-150}
IMAGE=maven:3-eclipse-temurin-25
HF=https://huggingface.co
mkdir -p "$WORK/models"
cd "$WORK"

fetch() { [ -s "models/$2" ] || curl -fL --retry 3 -o "models/$2" "$1"; }
fetch "$HF/Xenova/multilingual-e5-small/resolve/main/onnx/model_quantized.onnx" model_quantized.onnx
fetch "$HF/intfloat/multilingual-e5-small/resolve/main/sentencepiece.bpe.model" sentencepiece.bpe.model
[ -z "${FP32:-}" ] || fetch "$HF/Xenova/multilingual-e5-small/resolve/main/onnx/model.onnx" model.onnx
CHUNKS=""
if [ -n "${CHUNKS_FILE:-}" ]; then cp "$CHUNKS_FILE" chunks.txt; CHUNKS=/w/chunks.txt; fi

cat > pom.xml <<'EOF'
<project xmlns="http://maven.apache.org/POM/4.0.0">
  <modelVersion>4.0.0</modelVersion>
  <groupId>bench</groupId><artifactId>bench</artifactId><version>1</version>
  <dependencies>
    <dependency><groupId>com.microsoft.onnxruntime</groupId><artifactId>onnxruntime</artifactId><version>1.31.0</version></dependency>
    <dependency><groupId>ai.djl.sentencepiece</groupId><artifactId>sentencepiece</artifactId><version>0.39.0</version></dependency>
    <dependency><groupId>org.slf4j</groupId><artifactId>slf4j-nop</artifactId><version>2.0.17</version></dependency>
  </dependencies>
</project>
EOF

cat > Bench.java <<'EOF'
import ai.djl.sentencepiece.SpProcessor;
import ai.djl.sentencepiece.SpTokenizer;
import ai.onnxruntime.*;
import java.nio.file.*;
import java.util.*;

/** run <model> <sp> <threads> <n> [chunks] | compare <int8> <fp32> <sp> <n> [chunks] */
public class Bench {
    static final String[] SENTENCES = {
        "Welcome to the market, all vendors are verified and orders ship within 48 hours.",
        "Bem-vindo ao fórum, leia as regras antes de publicar e use sempre PGP nas mensagens.",
        "This hidden service mirrors the main site; check the signed canary before you log in.",
        "Aceitamos bitcoin e monero, o saldo é liberado depois da confirmação na blockchain.",
        "Our email provider keeps no logs and works over Tor without JavaScript.",
        "Bienvenido al foro, aquí se discuten temas de privacidad y seguridad informática.",
        "The admin posted a new PGP key after the old one expired last month.",
        "Lista de serviços .onion atualizada diariamente, com links verificados pela comunidade.",
        "Customer support answers tickets in English, Russian and German every weekday.",
        "Este site não armazena endereços IP e todo o conteúdo é servido por HTTPS e Tor.",
        "Escrow is mandatory for new vendors until they complete fifty successful sales.",
        "A comunidade discute VPNs, carteiras de criptomoedas e como evitar golpes de phishing.",
    };
    static final int LINK_PERCENT = Integer.getInteger("linkPercent", 5); // ~30% of chunks truncated at 512 tokens; real dev corpus: 35-46%
    static final String[] QUERIES = {
        "sites que vendem passaportes falsos", "anonymous email over tor", "public monero node",
        "como verificar um warrant canary", "vps that accepts bitcoin", "fórum de privacidade",
    };

    public static void main(String[] a) throws Exception {
        if (a[0].equals("compare")) compare(a); else run(a);
    }

    static List<String> chunks(int n, String file) throws Exception {
        if (file != null) {
            return Files.readAllLines(Path.of(file)).stream().filter(s -> !s.isBlank()).limit(n).map(s -> "passage: " + s).toList();
        }
        Random r = new Random(42);
        List<String> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            StringBuilder sb = new StringBuilder();
            while (sb.length() < 2048) {
                sb.append(SENTENCES[r.nextInt(SENTENCES.length)]).append(' ');
                if (r.nextInt(100) < LINK_PERCENT) { // onion links tokenize densely, like the real link listings
                    sb.append("http://");
                    for (int k = 0; k < 56; k++) sb.append("abcdefghijklmnopqrstuvwxyz234567".charAt(r.nextInt(32)));
                    sb.append(".onion/ ");
                }
            }
            out.add("passage: " + sb.substring(0, 2048)); // the core's chunk-size 512 x 4 chars
        }
        return out;
    }

    static OrtSession session(OrtEnvironment env, String model, int threads) throws OrtException {
        var o = new OrtSession.SessionOptions();
        o.setIntraOpNumThreads(threads);
        o.setInterOpNumThreads(1);
        return env.createSession(model, o);
    }

    static void run(String[] a) throws Exception {
        long t0 = System.nanoTime();
        SpProcessor sp = new SpTokenizer(Path.of(a[2])).getProcessor();
        var env = OrtEnvironment.getEnvironment();
        int threads = Integer.parseInt(a[3]), n = Integer.parseInt(a[4]);
        var s = session(env, a[1], threads);
        long loadMs = (System.nanoTime() - t0) / 1_000_000;
        List<String> cs = chunks(n, a.length > 5 ? a[5] : null);
        n = cs.size();
        embed(env, s, sp, cs.get(0)); // warm-up
        int truncated = 0;
        for (String c : cs) if (sp.encode(c).length > 510) truncated++;
        double[] lat = new double[n];
        long t1 = System.nanoTime();
        for (int i = 0; i < n; i++) {
            long t = System.nanoTime();
            embed(env, s, sp, cs.get(i));
            lat[i] = (System.nanoTime() - t) / 1e6;
        }
        double perSec = n / ((System.nanoTime() - t1) / 1e9);
        double[] q = new double[200];
        for (int i = 0; i < q.length; i++) {
            long t = System.nanoTime();
            embed(env, s, sp, "query: " + QUERIES[i % QUERIES.length]);
            q[i] = (System.nanoTime() - t) / 1e6;
        }
        Arrays.sort(lat);
        Arrays.sort(q);
        Runtime rt = Runtime.getRuntime();
        System.out.printf("model=%s threads=%d chunks=%d load=%dms chunks/s=%.1f chunk_p50=%.0fms chunk_p95=%.0fms query_p95=%.1fms truncated=%d/%d peak_rss=%s heap=%dMB%n",
                Path.of(a[1]).getFileName(), threads, n, loadMs, perSec, lat[n / 2], lat[(int) (n * 0.95)], q[(int) (q.length * 0.95)],
                truncated, n, peakRss(), (rt.totalMemory() - rt.freeMemory()) >> 20);
    }

    static void compare(String[] a) throws Exception {
        SpProcessor sp = new SpTokenizer(Path.of(a[3])).getProcessor();
        var env = OrtEnvironment.getEnvironment();
        var int8 = session(env, a[1], 2);
        var fp32 = session(env, a[2], 2);
        List<String> cs = chunks(Integer.parseInt(a[4]), a.length > 5 ? a[5] : null);
        double sum = 0, min = 1;
        for (String c : cs) {
            double cos = cosine(embed(env, int8, sp, c), embed(env, fp32, sp, c));
            sum += cos;
            min = Math.min(min, cos);
        }
        System.out.printf("int8_vs_fp32 chunks=%d mean_cosine=%.4f min_cosine=%.4f%n", cs.size(), sum / cs.size(), min);
    }

    /** XLM-R ids: SentencePiece id + 1 (fairseq offset), <s>=0, </s>=2, <unk>=3; mean pooling. */
    static float[] embed(OrtEnvironment env, OrtSession s, SpProcessor sp, String text) throws OrtException {
        int[] p = sp.encode(text);
        int len = Math.min(p.length, 510) + 2;
        long[][] ids = new long[1][len], mask = new long[1][len];
        ids[0][0] = 0;
        ids[0][len - 1] = 2;
        for (int i = 1; i < len - 1; i++) ids[0][i] = p[i - 1] == 0 ? 3 : p[i - 1] + 1;
        Arrays.fill(mask[0], 1);
        Map<String, OnnxTensor> in = new HashMap<>();
        in.put("input_ids", OnnxTensor.createTensor(env, ids));
        in.put("attention_mask", OnnxTensor.createTensor(env, mask));
        if (s.getInputNames().contains("token_type_ids")) in.put("token_type_ids", OnnxTensor.createTensor(env, new long[1][len]));
        try (var r = s.run(in)) {
            float[][] h = ((float[][][]) r.get(0).getValue())[0];
            float[] out = new float[h[0].length];
            for (float[] row : h) for (int d = 0; d < out.length; d++) out[d] += row[d] / len;
            return out;
        } finally {
            in.values().forEach(OnnxTensor::close);
        }
    }

    static double cosine(float[] x, float[] y) {
        double dot = 0, nx = 0, ny = 0;
        for (int i = 0; i < x.length; i++) { dot += x[i] * y[i]; nx += x[i] * x[i]; ny += y[i] * y[i]; }
        return dot / Math.sqrt(nx * ny);
    }

    static String peakRss() throws Exception {
        Path status = Path.of("/proc/self/status");
        if (!Files.exists(status)) return "n/a";
        for (String l : Files.readAllLines(status)) if (l.startsWith("VmHWM:")) return (Long.parseLong(l.replaceAll("\\D", "")) >> 10) + "MB";
        return "n/a";
    }
}
EOF

in_docker() {
  docker run --rm ${DOCKER_PLATFORM:+--platform $DOCKER_PLATFORM} --entrypoint "" -u "$(id -u):$(id -g)" -e HOME=/w \
    -v "$WORK":/w -w /w "$IMAGE" "$@"
}
java_run() { in_docker java --enable-native-access=ALL-UNNAMED -cp 'classes:lib/*' Bench "$@" 2>&1 | grep -v '^WARNING'; }

[ -d lib ] || in_docker mvn -q -Dmaven.repo.local=/w/m2 dependency:copy-dependencies -DoutputDirectory=lib
in_docker javac -cp 'lib/*' -d classes Bench.java

CPUS=$(getconf _NPROCESSORS_ONLN)
{
  echo "=== $(hostname) $(date -u +%FT%TZ)"
  echo "cpu: $(grep -m1 'model name' /proc/cpuinfo 2>/dev/null | cut -d: -f2- | xargs) | arch $(uname -m) | nproc $CPUS"
  echo "simd: $(grep -o -w -E 'avx2|avx512f|avx512_vnni|avx_vnni|amx_int8' /proc/cpuinfo 2>/dev/null | sort -u | tr '\n' ' ')"
  echo "mem: $(free -m 2>/dev/null | awk '/Mem:/ {print $2 " MB total, " $7 " MB available"}')"
  echo "load: $(cut -d' ' -f1-3 /proc/loadavg 2>/dev/null)"
  MODELS="model_quantized.onnx"
  [ -z "${FP32:-}" ] || MODELS="$MODELS model.onnx"
  for m in $MODELS; do
    for t in 1 2 4 8; do
      [ "$t" -le "$CPUS" ] || continue
      java_run run "models/$m" models/sentencepiece.bpe.model "$t" "$N" $CHUNKS
    done
  done
  [ -z "${FP32:-}" ] || java_run compare models/model_quantized.onnx models/model.onnx models/sentencepiece.bpe.model 50 $CHUNKS
} | tee -a results.txt
