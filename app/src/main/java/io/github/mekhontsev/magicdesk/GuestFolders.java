package io.github.mekhontsev.magicdesk;

import android.content.Context;
import android.content.Intent;
import io.github.mekhontsev.magicdesk.documents.AndroidFoldersActivity;

public final class GuestFolders extends AndroidFoldersActivity {
    static void show(Context context, GuestEnvironmentCatalog.Entry entry) {
        var executor=new CommandExecution(context,entry.backend());
        context.startActivity(new Intent(context,GuestFolders.class).putExtra(SCOPE,executor.scope+":"+entry.store()));
    }
    @Override protected void changed(String scope) throws Exception {
        GuestFolderPublication.publish(getApplicationContext(),scope,true);
    }
}
