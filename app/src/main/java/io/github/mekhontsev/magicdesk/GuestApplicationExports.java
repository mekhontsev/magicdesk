package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.net.Uri;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Packages a private runtime and explicitly copies data; it grants no command-service authority. */
final class GuestApplicationExports {
    record Prepared(LauncherExportStore.Entry entry, Uri apk, Uri archive) { }

    static LauncherExportStore.Target capture(Context context,GuestEnvironmentCatalog.Entry entry) {
        var execution=new CommandExecution(context,entry.backend());
        if (entry.backend()==DesktopExecBackend.TERMUX) throw new IllegalArgumentException("A Shroot environment is required");
        return new LauncherExportStore.Target("environment",entry.store(),"",-1,execution.uid,
                AppProfile.current(context).serialNumber);
    }

    static Prepared prepare(Context context,LauncherExportStore.Target target,OperationResources resources) throws Exception {
        if (!target.source().equals("environment") || !target.termuxPackage().isEmpty())
            throw new IllegalArgumentException("Invalid environment export");
        LauncherExports.requireIdentity(context,target);
        var execution=new CommandExecution(context,target.shellUid()>=10000 ? DesktopExecBackend.APP : DesktopExecBackend.SHELL);
        if (execution.uid!=target.shellUid()) throw new SecurityException("Environment executor changed");
        var environments=GuestEnvironmentCatalog.parse(GuestEnvironmentOperations.read(execution,"","list","--json"),execution.backend);
        var source=environments.stream().filter(value -> value.store().equals(target.path())).findFirst()
                .orElseThrow(() -> new IOException("Linux environment is no longer installed"));
        var identity=LauncherSigningIdentity.load();
        var entry=LauncherExports.store(context).put(target,source.name(),identity.fingerprint());
        boolean installed=LauncherExports.installed(context,entry);
        if (installed && !LauncherExports.signedByUs(context,entry)) throw new SecurityException("Installed package has a different signing identity");
        Uri archive=installed ? null : backup(context,execution,source,resources);
        requireActive(resources);
        Path unsigned=Files.createTempFile(context.getCacheDir().toPath(),"linux-unsigned-",".apk");
        Path signed=Files.createTempFile(context.getCacheDir().toPath(),"linux-signed-",".apk");
        try {
            var libraries=new LinkedHashMap<String,Path>();
            Path nativeDir=Path.of(context.getApplicationInfo().nativeLibraryDir);
            for (String file : GuestRuntimeArtifacts.FILES)
                if (!file.equals("libmagicdesk_command_client.so")) libraries.put(file,nativeDir.resolve(file));
            for (String file : java.util.List.of("libmagicdesk_mksh.so","libmagicdesk_pty_bridge.so"))
                libraries.put(file,nativeDir.resolve(file));
            byte[] icon=icon(context);
            byte[] config=new JSONObject().put("format",1).put("sourceId",source.id()).put("name",source.name())
                    .put("hostPackage",context.getPackageName()).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            try (var template=context.getAssets().open("launcher/guest-template.apk")) {
                LauncherApk.assemble(template,Map.of("io.github.mekhontsev.magicdesk.guesttemplate",entry.packageName(context.getPackageName()),
                        "MAGICDESK_LINUX_LABEL",source.name()),icon,libraries,config,notices(context),unsigned);
            }
            LauncherApk.sign(unsigned,signed,identity.key(),identity.certificate());
            requireActive(resources);
            Uri apk=GeneratedContentProvider.publish(context,"Linux-application.apk",output -> Files.copy(signed,output));
            return new Prepared(entry,apk,archive);
        } finally { Files.deleteIfExists(unsigned); Files.deleteIfExists(signed); }
    }

    private static Map<String,byte[]> notices(Context context) throws IOException {
        var result=new LinkedHashMap<String,byte[]>();
        for (String directory : java.util.List.of("licenses/guest-images","licenses/mksh","licenses/magicdesk"))
            collectNotices(context,directory,result);
        try (var input=context.getAssets().open("termux-terminal-emulator/LICENSE")) {
            result.put("assets/licenses/terminal-emulator.txt",input.readAllBytes());
        }
        result.put("assets/licenses/sources.txt",("MagicDesk: https://github.com/mekhontsev/magicdesk\n"
                +"Build source: "+BuildConfig.SOURCE_ID+"\nShroot: https://github.com/mekhontsev/shroot\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return result;
    }

    private static void collectNotices(Context context,String directory,Map<String,byte[]> result) throws IOException {
        String[] entries=context.getAssets().list(directory);
        if (entries==null || entries.length==0) throw new IOException("Missing runtime licenses: "+directory);
        for (String name : entries) {
            String path=directory+"/"+name;
            String[] children=context.getAssets().list(path);
            if (children!=null && children.length>0) { collectNotices(context,path,result); continue; }
            try (var input=context.getAssets().open(path)) {
                byte[] bytes=input.readNBytes(1024*1024+1);
                if (bytes.length>1024*1024) throw new IOException("Runtime notice is too large");
                result.put("assets/"+path,bytes);
            }
        }
    }

    private static byte[] icon(Context context) throws IOException {
        var bitmap=android.graphics.Bitmap.createBitmap(192,192,android.graphics.Bitmap.Config.ARGB_8888);
        try {
            var drawable=context.getDrawable(R.mipmap.ic_launcher).mutate();
            drawable.setBounds(0,0,192,192); drawable.draw(new android.graphics.Canvas(bitmap));
            var output=new java.io.ByteArrayOutputStream();
            if (!bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output)) throw new IOException("Cannot encode Linux icon");
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static void requireActive(OperationResources resources) throws IOException {
        if (resources.isClosed()) throw new IOException("Export cancelled");
    }

    private static Uri backup(Context context,CommandExecution execution,GuestEnvironmentCatalog.Entry source,OperationResources resources) throws IOException {
        String file=execution.temporaryDirectory()+"/linux-export-"+java.util.UUID.randomUUID()+".tar.zst";
        try (var owner=resources.reserve(); var session=execution.session("")) {
            owner.attach(session::close);
            requireActive(resources);
            var result=session.execute(GuestEnvironmentCatalog.command("backup",source.name(),file),null);
            if (result.exitCode()!=0) throw new IOException(result.stderr().isBlank() ? result.output() : result.stderr());
            execution.requireCurrent();
            return GeneratedContentProvider.publishArchive(context,source.name()+".tar.zst",output -> {
                var copied=session.execute("cat "+ShellCommandLine.quote(file),(bytes,offset,count) -> {
                    requireActive(resources); output.write(bytes,offset,count);
                });
                if (copied.exitCode()!=0) throw new IOException(copied.stderr());
            });
        } finally {
            try (var cleanup=execution.session("")) {
                var result=cleanup.execute("rm -f "+ShellCommandLine.quote(file),null);
                if (result.exitCode()!=0) android.util.Log.w("GuestApplicationExports","Cannot remove owned archive: "+result.stderr());
            }
        }
    }
    private GuestApplicationExports() { }
}
