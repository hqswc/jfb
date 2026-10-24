package com.jfbg.scoreboard;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * 本地数据仓库（SQLite）。
 *
 * 表结构：
 *   members(id TEXT PK, name TEXT, sort_index INT)
 *   records(id TEXT PK, member_id TEXT, ts TEXT, change REAL, category TEXT,
 *           reason TEXT, operator TEXT, deleted INT, deleted_at TEXT)
 *   meta(key TEXT PK, value TEXT)
 *
 * 设计取舍：
 *   - 记录用独立表而不是存成一个 JSON 字段，这样单条撤回/编辑只改一行，
 *     不会因为并发写入把别人的改动覆盖掉。
 *   - 首次启动时从 assets/seed/initial-data.json 灌入历史数据。
 */
final class ScoreStore extends SQLiteOpenHelper {

    private static final String TAG = "ScoreStore";
    private static final String DB_NAME = "scoreboard.db";
    private static final int DB_VERSION = 1;

    private static final String META_SEEDED = "seed_loaded";
    private static final String META_PASSWORD = "admin_password";
    private static final String DEFAULT_PASSWORD = "admin2024";

    private final Context context;

    ScoreStore(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
        this.context = context;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE members ("
                + "id TEXT PRIMARY KEY,"
                + "name TEXT NOT NULL,"
                + "sort_index INTEGER NOT NULL DEFAULT 0)");
        db.execSQL("CREATE TABLE records ("
                + "id TEXT PRIMARY KEY,"
                + "member_id TEXT NOT NULL,"
                + "ts TEXT NOT NULL,"
                + "change REAL NOT NULL DEFAULT 0,"
                + "category TEXT,"
                + "reason TEXT,"
                + "operator TEXT,"
                + "deleted INTEGER NOT NULL DEFAULT 0,"
                + "deleted_at TEXT)");
        db.execSQL("CREATE INDEX idx_records_member ON records(member_id)");
        db.execSQL("CREATE TABLE meta (key TEXT PRIMARY KEY, value TEXT)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 目前只有 v1；未来结构变更在这里做迁移，绝不清库
    }

    /** 首次启动时灌入种子数据；已灌过则跳过。 */
    void ensureSeeded() {
        SQLiteDatabase db = getWritableDatabase();
        if (hasSeeded(db)) return;

        db.beginTransaction();
        try {
            String json = readAsset("seed/initial-data.json");
            if (json != null) {
                JSONObject root = new JSONObject(json);
                JSONArray members = root.optJSONArray("members");
                if (members != null) {
                    for (int i = 0; i < members.length(); i++) {
                        JSONObject m = members.getJSONObject(i);
                        ContentValues mv = new ContentValues();
                        mv.put("id", m.optString("id"));
                        mv.put("name", m.optString("name"));
                        mv.put("sort_index", i);
                        db.insertWithOnConflict("members", null, mv, SQLiteDatabase.CONFLICT_REPLACE);

                        JSONArray recs = m.optJSONArray("records");
                        if (recs != null) {
                            for (int k = 0; k < recs.length(); k++) {
                                putRecord(db, m.optString("id"), recs.getJSONObject(k));
                            }
                        }
                    }
                }
                Log.i(TAG, "seeded " + (members == null ? 0 : members.length()) + " members");
            }
            setMeta(db, META_SEEDED, "1");
            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "seed failed", e);
        } finally {
            db.endTransaction();
        }
    }

    private boolean hasSeeded(SQLiteDatabase db) {
        Cursor c = db.query("meta", new String[]{"value"}, "key=?", new String[]{META_SEEDED}, null, null, null);
        boolean seeded = c.moveToFirst();
        c.close();
        return seeded;
    }

    private void putRecord(SQLiteDatabase db, String memberId, JSONObject rec) {
        ContentValues rv = new ContentValues();
        rv.put("id", rec.optString("id"));
        rv.put("member_id", memberId);
        rv.put("ts", rec.optString("timestamp"));
        rv.put("change", rec.optDouble("change", 0));
        rv.put("category", rec.optString("category"));
        rv.put("reason", rec.optString("reason"));
        rv.put("operator", rec.optString("operator", "管理员"));
        rv.put("deleted", rec.optBoolean("deleted", false) ? 1 : 0);
        rv.put("deleted_at", rec.optString("deletedAt", null));
        db.insertWithOnConflict("records", null, rv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** 读出全部成员及其记录，组装成前端要的 JSON。 */
    JSONObject loadAll() throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        JSONArray members = new JSONArray();

        Cursor mc = db.query("members", new String[]{"id", "name"},
                null, null, null, null, "sort_index ASC");
        while (mc.moveToNext()) {
            String id = mc.getString(0);
            JSONObject m = new JSONObject();
            m.put("id", id);
            m.put("name", mc.getString(1));

            JSONArray recs = new JSONArray();
            Cursor rc = db.query("records",
                    new String[]{"id", "ts", "change", "category", "reason", "operator", "deleted", "deleted_at"},
                    "member_id=?", new String[]{id}, null, null, "ts ASC");
            double total = 0;
            while (rc.moveToNext()) {
                JSONObject r = new JSONObject();
                r.put("id", rc.getString(0));
                r.put("timestamp", rc.getString(1));
                double change = rc.getDouble(2);
                r.put("change", change);
                r.put("category", rc.getString(3) == null ? "" : rc.getString(3));
                r.put("reason", rc.getString(4) == null ? "" : rc.getString(4));
                r.put("operator", rc.getString(5) == null ? "" : rc.getString(5));
                boolean deleted = rc.getInt(6) != 0;
                if (deleted) {
                    r.put("deleted", true);
                    String dat = rc.getString(7);
                    if (dat != null) r.put("deletedAt", dat);
                } else {
                    total += change;   // 只有未删除的计入总分
                }
                recs.put(r);
            }
            rc.close();
            m.put("records", recs);
            m.put("totalScore", total);
            members.put(m);
        }
        mc.close();

        JSONObject out = new JSONObject();
        out.put("members", members);
        return out;
    }

    /** 覆盖写入给定成员集合（用于批量操作/导入）。 */
    void replaceAll(JSONObject root) throws JSONException {
        JSONArray members = root.optJSONArray("members");
        if (members == null) return;
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("records", null, null);
            db.delete("members", null, null);
            for (int i = 0; i < members.length(); i++) {
                JSONObject m = members.getJSONObject(i);
                ContentValues mv = new ContentValues();
                mv.put("id", m.optString("id"));
                mv.put("name", m.optString("name"));
                mv.put("sort_index", i);
                db.insertWithOnConflict("members", null, mv, SQLiteDatabase.CONFLICT_REPLACE);
                JSONArray recs = m.optJSONArray("records");
                if (recs != null) {
                    for (int k = 0; k < recs.length(); k++) {
                        putRecord(db, m.optString("id"), recs.getJSONObject(k));
                    }
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void upsertMember(String id, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues mv = new ContentValues();
        mv.put("id", id);
        mv.put("name", name);
        Cursor c = db.rawQuery("SELECT COALESCE(MAX(sort_index),-1)+1 FROM members", null);
        int next = 0;
        if (c.moveToFirst()) next = c.getInt(0);
        c.close();
        mv.put("sort_index", next);
        db.insertWithOnConflict("members", null, mv, SQLiteDatabase.CONFLICT_REPLACE);
    }

    void deleteMember(String id) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("records", "member_id=?", new String[]{id});
            db.delete("members", "id=?", new String[]{id});
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    void renameMember(String id, String name) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues mv = new ContentValues();
        mv.put("name", name);
        db.update("members", mv, "id=?", new String[]{id});
    }

    /** 写入一条记录（撤回=soft delete，编辑=UPDATE 同一行）。 */
    void putRecordForMember(String memberId, JSONObject rec) {
        putRecord(getWritableDatabase(), memberId, rec);
    }

    /** 软删除一条记录。 */
    void softDeleteRecord(String recordId) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("deleted", 1);
        v.put("deleted_at", nowIso());
        db.update("records", v, "id=?", new String[]{recordId});
    }

    /** 管理员密码：首次取默认值并落库，之后可改。 */
    String getPassword() {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.query("meta", new String[]{"value"}, "key=?", new String[]{META_PASSWORD}, null, null, null);
        String pwd = null;
        if (c.moveToFirst()) pwd = c.getString(0);
        c.close();
        if (pwd == null || pwd.isEmpty()) {
            pwd = DEFAULT_PASSWORD;
            setMeta(db, META_PASSWORD, pwd);
        }
        return pwd;
    }

    void setPassword(String pwd) {
        setMeta(getWritableDatabase(), META_PASSWORD, pwd);
    }

    private void setMeta(SQLiteDatabase db, String key, String value) {
        ContentValues v = new ContentValues();
        v.put("key", key);
        v.put("value", value);
        db.insertWithOnConflict("meta", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    private String nowIso() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US).format(new Date());
    }

    private String readAsset(String path) {
        try (InputStream in = context.getAssets().open(path)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
