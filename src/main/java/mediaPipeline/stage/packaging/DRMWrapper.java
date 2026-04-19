package mediaPipeline.stage.packaging;

import mediaPipeline.model.StageResult;
import mediaPipeline.stage.BaseStage;
import mediaPipeline.stage.PipelineContext;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.*;

public class DRMWrapper extends BaseStage {

    private static final String ALGORITHM = "AES/CTR/NoPadding";
    private static final int KEY_SIZE = 256;
    private static final int IV_SIZE = 16;
    private static final int BUFFER = 64 * 1024;

    @Override
    public String name() {
        return "DRMWrapper";
    }

    @Override
    protected StageResult run(PipelineContext ctx) {
        long t = System.currentTimeMillis();

        @SuppressWarnings("unchecked")
        List<String> encodedAssets = (List<String>) ctx.get("encoded_assets");

        if (encodedAssets == null || encodedAssets.isEmpty()) {
            return StageResult.fail(name(),
                    "encoded_assets missing: Transcoder must run first",
                    elapsed(t));
        }

        try {
            KeyGenerator kg = KeyGenerator.getInstance("AES");
            kg.init(KEY_SIZE);
            SecretKey key = kg.generateKey();

            List<Map<String, String>> encryptedAssets = new ArrayList<>();

            for (String assetPath : encodedAssets) {
                Path src = Path.of(assetPath);
                Path enc = Path.of(assetPath + ".enc");

                byte[] iv = new byte[IV_SIZE];
                new SecureRandom().nextBytes(iv);

                encrypt(src, enc, key, iv);

                encryptedAssets.add(Map.of(
                        "path", enc.toString(),
                        "iv", Base64.getEncoder().encodeToString(iv)
                ));

                log.info("Encrypted: {}", enc.getFileName());
            }

            ctx.put("drm_key", Base64.getEncoder().encodeToString(key.getEncoded()));
            ctx.put("encrypted_assets", encryptedAssets);
            ctx.put("drm_method", "AES-256-CTR");

            log.info("DRM complete: {} assets encrypted", encryptedAssets.size());

            return StageResult.ok(name(), elapsed(t));

        } catch (Exception e) {
            return StageResult.fail(name(),
                    "DRM failed: " + e.getMessage(),
                    elapsed(t));
        }
    }

    private void encrypt(Path src, Path dst, SecretKey key, byte[] iv) throws Exception {
        Cipher cipher = Cipher.getInstance(ALGORITHM);
        cipher.init(Cipher.ENCRYPT_MODE, key, new IvParameterSpec(iv));

        try (InputStream in = Files.newInputStream(src);
             OutputStream out = Files.newOutputStream(dst)) {

            byte[] buf = new byte[BUFFER];
            int read;

            while ((read = in.read(buf)) != -1) {
                byte[] enc = cipher.update(buf, 0, read);
                if (enc != null) out.write(enc);
            }

            byte[] finalBytes = cipher.doFinal();
            if (finalBytes != null) out.write(finalBytes);
        }
    }
}