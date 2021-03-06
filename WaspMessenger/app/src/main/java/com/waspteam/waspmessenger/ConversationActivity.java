package com.waspteam.waspmessenger;


import java.net.MalformedURLException;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.TextUtils;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.graphics.Color;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.security.SecureRandom;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.microsoft.windowsazure.mobileservices.table.MobileServiceTable;
import com.microsoft.windowsazure.mobileservices.http.OkHttpClientFactory;
import com.squareup.okhttp.OkHttpClient;

import static com.microsoft.windowsazure.mobileservices.table.query.QueryOperations.*;

import com.microsoft.windowsazure.mobileservices.MobileServiceClient;


public class ConversationActivity extends AppCompatActivity {

    private EditText mConversationName, mConversationCode;
    private MobileServiceClient mClient;
    private MobileServiceTable<Conversation> mConvoTable;
    private MobileServiceTable<User> mUserTable;
    private MobileServiceTable<ConversationKey> mKeyTable;
    private MobileServiceTable<Message> mMessageTable;
    private String mUsername;
    private String mHandle = "";
    ConversationAdapter mAdapter;

    //Handles all key exchange calculation and key retrieval from memory
    private CryptoGenerator cryptoGen;

    static final String AB = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    static SecureRandom rnd = new SecureRandom();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_conversation);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        mConversationName = (EditText) findViewById(R.id.newConversationNickname);
        mConversationCode = (EditText) findViewById(R.id.newConversationHandleCode);

        //Gets String passed the login activity
        Intent intent = getIntent();
        mUsername = intent.getStringExtra("username");
        //mHandle = intent.getStringExtra("handle");

        //This is the crypto generator that will be doing key exchange and fetching keys from file storage
        cryptoGen = new CryptoGenerator(this.getApplicationContext(), mUsername);

        mAdapter = new ConversationAdapter(this, R.layout.row_conversation);
        ListView listViewConversation = (ListView) findViewById(R.id.listView_conversation);
        listViewConversation.setAdapter(mAdapter);

        //Startup Azure Connection
        try {
            // Create the Mobile Service Client instance, using app URL
            mClient = new MobileServiceClient(
                    "https://waspsmessenger.azurewebsites.net",
                    this);
            //setup table references
            mConvoTable = mClient.getTable(Conversation.class);
            mUserTable = mClient.getTable(User.class);
            mMessageTable = mClient.getTable(Message.class);
            mKeyTable = mClient.getTable(ConversationKey.class);

            mClient.setAndroidHttpClientFactory(new OkHttpClientFactory() {
                @Override
                public OkHttpClient createOkHttpClient() {
                    OkHttpClient client = new OkHttpClient();
                    client.setReadTimeout(20, TimeUnit.SECONDS);
                    client.setWriteTimeout(20, TimeUnit.SECONDS);
                    return client;
                }
            });

            refreshItemsFromTable();

        } catch (MalformedURLException e) {
            createAndShowDialog(new Exception("There was an error creating the Mobile Service. Verify the URL"), "Error");
        } catch (Exception e) {
            createAndShowDialog(e, "Error");
        }

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                LinearLayout editLayout = (LinearLayout) findViewById(R.id.newConversationLayout);

                if (editLayout.getVisibility() == LinearLayout.GONE) {
                    editLayout.setVisibility(LinearLayout.VISIBLE);
                } else {
                    if (validInput(mConversationName.getText()) || validInput(mConversationCode.getText())) {
                        Snackbar.make(view, "Please enter a valid name and contact code.", Snackbar.LENGTH_LONG)
                                .setAction("Action", null).show();
                    } else {
                        //Add the conversation to the server
                        addConversation();
                        mConversationName.setText("");
                        mConversationCode.setText("");
                        editLayout.setVisibility(LinearLayout.GONE);
                        editLayout.requestFocus();
                    }
                }
            }
        });


    }
    public void addConversation() {
        try {
            final Conversation addConversation = new Conversation();
            String handle = mConversationCode.getText().toString();
            String nick = mConversationName.getText().toString();
            addToTable(addConversation, handle, nick);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    //checks if the handle is a valid user
    private void addToTable(final Conversation addConversation, final String handle, final String nick) {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {

                try {
                    //doingCrypto=true;
                    final List<User> results = checkUser(handle); //checks for handlecode
                    final List<User> results2 = checkUser2(handle); //checks for username

                    if ((results.size() > 0 || results2.size() > 0) && !isUser(handle)) {
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                final User u;
                                mAdapter.clear();

                                addConversation.mNicknameA = nick;
                                if (results.size() > 0) { //start anon conversation if its a handlecode
                                    u = results.get(0);
                                    addConversation.mNicknameB = u.handle;
                                    addConversation.mHandleB = handle;
                                    addConversation.mHandleA = mHandle;
                                } else //start known conversation if its a username
                                {
                                    u = results2.get(0);
                                    addConversation.mNicknameB = u.username;
                                    addConversation.mHandleB = handle;
                                    addConversation.mHandleA = mUsername;
                                }
                                ListenableFuture<Conversation> insertResults = mConvoTable.insert(addConversation); //insert conversation to table

                                Futures.addCallback(insertResults, new FutureCallback<Conversation>()
                                {
                                    @Override
                                    public void onFailure(Throwable e) {
                                        //Failure is already handled in main thread
                                    }

                                    @Override
                                    public void onSuccess(Conversation result) {

                                        try {
                                            //DO PHASE 1 OF DIFFIE HELLMAN CRYPTOGRAPHIC KEY EXCHANGE
                                            ConversationKey addKeySet = new ConversationKey();
                                            addKeySet.mDate = result.mDate;
                                            addKeySet.mId = result.mId;
                                            addKeySet.mKeyA = cryptoGen.Phase1(result.mId + result.mDate);
                                            mKeyTable.insert(addKeySet);
                                        } catch (Exception e) {
                                            e.printStackTrace();
                                        }
                                    }

                                });


                                Snackbar.make(findViewById(R.id.fab), "Conversation sent! It will appear after the other user recieves the confirmation.", Snackbar.LENGTH_LONG)
                                        .setAction("Action", null).show();

                                refreshItemsFromTable();
                            }
                        });

                    } else
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                AlertDialog.Builder builder = new AlertDialog.Builder(ConversationActivity.this);
                                builder.setMessage("Invalid Handle ID")
                                        .setNegativeButton("Try again", null)
                                        .create().show();
                            }
                        });

                    return null;


                }
                catch (final Exception e) {
                    createAndShowDialogFromTask(e, "Error");
                }
                return null;
            }

        };
        runAsyncTask(task);
    }

    //refresh conversation lists
    public void refreshItemsFromTable() {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>()
        {
            @Override
            protected Void doInBackground(Void... params)
            {

                try
                {
                    final List<User> user = mUserTable.where().field("username").eq(mUsername).execute().get();
                    final User u = user.get(0);
                    mHandle = u.handle;
                    final List<Conversation> results = refreshItemsFromConvoTable(); //find user's handle in handleA conversations
                    final List<Conversation> results2 = refreshItemsFromConvoTable2(); //find user's handle in handleB conversations

                    //Do key exchange work for user in handleA
                    for(Conversation item: results)
                    {
                        if (item.mReadyB && !item.mReadyA) {

                            //Do Phase 3 of key exchange
                            List<ConversationKey> keyTuple = mKeyTable.where(field("id").eq(val(item.mId)))
                                                                        .and(field("convCreatedAt").eq(val(item.mDate)))
                                                                        .execute().get();

                            if(keyTuple.size()!=1)
                            {
                                //Problem retrieving the proper key
                                throw new Exception();
                            }

                            if(cryptoGen.Phase3(item.mId + item.mDate, keyTuple.get(0).mKeyB))
                            {
                                item.mReadyA=true;
                                mConvoTable.update(item);
                            }
                            else
                            {
                                //The crypto failed
                                throw new Exception();
                            }
                        }
                    }

                    for(Conversation item2: results2)
                    {
                        if (!item2.mReadyA && !item2.mReadyB)
                        {

                            //Do Phase 2 of key Exchange

                            List<ConversationKey> keyList = mKeyTable.where(field("id").eq(val(item2.mId)))
                                                                        .and(field("convCreatedAt").eq(val(item2.mDate)))
                                                                        .execute().get();

                            if(keyList.size()!=1)
                            {
                                //Problem retrieving the proper key
                                throw new Exception();
                            }

                            //Get and send B Public Key to key table
                            keyList.get(0).mKeyB = cryptoGen.Phase2(item2.mId + item2.mDate, keyList.get(0).mKeyA);
                            mKeyTable.update(keyList.get(0));

                            //Update conversation as ready for B to send messages
                            item2.mReadyB=true;
                            mConvoTable.update(item2);

                        }
                    }

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run()
                        {
                            try {

                                mAdapter.clear();
                                //add it to the view depending on whether its in handle A or B, hence isExist()
                                for (Conversation item : results) {
                                    item.isExist = true;

                                    if (item.mReadyA) {
                                        mAdapter.add(item);
                                    }
                                }
                                for (Conversation item2 : results2)
                                {
                                    item2.isExist = false;

                                    if (item2.mReadyB)
                                    {
                                        mAdapter.add(item2);
                                    }
                                }
                            }
                            catch(Exception e)
                            {
                                e.printStackTrace();
                            }
                        }
                    });
                } catch (final Exception e) {
                    createAndShowDialogFromTask(e, "Error");
                }

                return null;
            }
        };

        runAsyncTask(task);
    }

    //start messaging activity
    public void startMessaging(View view, Conversation c)
    {
        Intent intent = new Intent(view.getContext(), MessagingActivity.class);
        Bundle bundle = new Bundle();
        bundle.putString("EXTRA_MYUSERNAME", mUsername);

        //if current user's handle is in handleA of the conversation, bundle extra variables to messaging
        if (c.isExist) {
            if(mUsername.equals(c.mHandleA)) {
                bundle.putString("EXTRA_MYHANDLE", mUsername);
            }
            else
            {
                bundle.putString("EXTRA_MYHANDLE", c.mHandleA);
            }
            bundle.putString("EXTRA_MYNICKNAME", c.getNicknameA());
            bundle.putString("EXTRA_TOHANDLE", c.mHandleB);
            bundle.putString("EXTRA_TONICK", c.getNicknameB());
        } else { //otherwise its in handleB's
            if(mUsername.equals(c.mHandleA)) {
                bundle.putString("EXTRA_MYHANDLE", mUsername);
            }
            else
            {
                bundle.putString("EXTRA_MYHANDLE", c.mHandleB);
            }
            bundle.putString("EXTRA_MYNICKNAME", c.getNicknameB());
            bundle.putString("EXTRA_TOHANDLE", c.mHandleA);
            bundle.putString("EXTRA_TONICK", c.getNicknameA());
        }

        //Fetch the secret key stored for this conversation
        String keyTag = c.mId+c.mDate;
        byte[] secretKey = cryptoGen.getSecretKey(keyTag);
        bundle.putByteArray("EXTRA_SECRETKEY",secretKey);

        intent.putExtras(bundle);
        view.getContext().startActivity(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        //generate a new handle
        if (id == R.id.generate_handle) {
            handleGeneration();
        } else if (id == R.id.view_handle) { //view current handle
            String h = mHandle;
            final Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), "Handle Code: " + h, Snackbar.LENGTH_LONG);
            snackbar.setAction("Close", new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    snackbar.dismiss();
                }
            })
                    .setActionTextColor(Color.WHITE).setDuration(100000).show();
        } else if (id == R.id.logout) { //logout
            this.finish();
            Intent intent = new Intent(this, LoginActivity.class);
            this.startActivity(intent);
        } else if (id == R.id.refreshConv) {
            refreshItemsFromTable();
        }

        return super.onOptionsItemSelected(item);
    }

    //generate a new handle
    public void handleGeneration() {
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            protected Void doInBackground(Void... objects) {
                try {
                    final List<User> user = mUserTable.where().field("username").eq(mUsername).execute().get();

                    final User u = user.get(0);
                    final List<Conversation> convosA = userConversations(mHandle);//find conversations where user's handle is in handleA
                    final List<Conversation> convosB = userConversationsB(mHandle); //find conversations where user's handle is in handleB

                    final List<Message> msgTo = findMsgFromTable(); //find messages where user's handle is in mTo
                    final List<Message> msgFrom = findMsgFromTable2(); //find messages where user's handle is in mFrom

                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {

                            final String newHandle = generateHandle(10); //generate new handle
                            u.handle = newHandle;
                            mHandle = newHandle; //update current user handle
                            mUserTable.update(u); //update handle in user's table

                            //update conversations and messages with the new handle
                            for (Conversation item : convosA) {
                                item.mHandleA = newHandle;
                                mConvoTable.update(item);
                            }
                            for (Conversation item2 : convosB) {
                                item2.mHandleB = newHandle;
                                mConvoTable.update(item2);
                            }

                            for (Message item3 : msgTo) {
                                item3.setTo(newHandle);
                                mMessageTable.update(item3);
                            }

                            for (Message item4 : msgFrom) {
                                item4.setFrom(newHandle);
                                mMessageTable.update(item4);
                            }
                            //refresh the list of conversations
                            refreshItemsFromTable();

                            //display new handle
                            final Snackbar snackbar = Snackbar.make(findViewById(android.R.id.content), "New Handle Code: " + newHandle, Snackbar.LENGTH_LONG);
                            snackbar.setAction("Close", new View.OnClickListener() {
                                @Override
                                public void onClick(View v) {
                                    snackbar.dismiss();
                                }
                            })
                                    .setActionTextColor(Color.WHITE).setDuration(100000).show();
                        }
                    });
                } catch (final Exception e) {
                    createAndShowDialogFromTask(e, "Error");
                }

                return null;
            }
        }.execute();
    }

    //generate a new handle using a secure random number generator
    private String generateHandle(int n) {
        StringBuilder sb = new StringBuilder(n);
        sb.append('$');
        for (int i = 0; i < n - 1; i++)
            sb.append(AB.charAt(rnd.nextInt(AB.length())));
        return sb.toString();
    }

    private List<Conversation> refreshItemsFromConvoTable() throws ExecutionException, InterruptedException {
        return mConvoTable.where().field("handleA").eq(val(mHandle)).or().field("handleA").eq(val(mUsername)).execute().get();
    }

    private List<Conversation> refreshItemsFromConvoTable2() throws ExecutionException, InterruptedException {
        return mConvoTable.where().field("handleB").eq(val(mHandle)).or().field("handleB").eq(val(mUsername)).execute().get();
    }

    private List<User> checkUser(String h) throws ExecutionException, InterruptedException {
        return mUserTable.where().field("handle").eq(val(h)).execute().get();
    }
    private List<User> checkUser2(String h) throws ExecutionException, InterruptedException {
        return mUserTable.where().field("username").eq(val(h)).execute().get();
    }

    private boolean isUser(String h) {

        return mHandle.equals(h) || mUsername.equals(h);
    }

    private List<Conversation> userConversations(String h) throws ExecutionException, InterruptedException {
        return mConvoTable.where().field("handleA").eq(val(h)).execute().get();
    }

    private List<Conversation> userConversationsB(String h) throws ExecutionException, InterruptedException {
        return mConvoTable.where().field("handleB").eq(val(h)).execute().get();
    }

    private List<Message> findMsgFromTable() throws ExecutionException, InterruptedException {
        return mMessageTable.where().field("mTo").eq(mHandle).execute().get();
    }

    private List<Message> findMsgFromTable2() throws ExecutionException, InterruptedException {
        return mMessageTable.where().field("mFrom").eq(mHandle).execute().get();
    }

    private void createAndShowDialog(Exception exception, String title) {
        Throwable ex = exception;
        if (exception.getCause() != null) {
            ex = exception.getCause();
        }
        createAndShowDialog(ex.getMessage(), title);
    }


    private void createAndShowDialog(final String message, final String title) {
        final AlertDialog.Builder builder = new AlertDialog.Builder(this);

        builder.setMessage(message);
        builder.setTitle(title);
        builder.create().show();
    }

    private void createAndShowDialogFromTask(final Exception exception, String title) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                createAndShowDialog(exception, "Error");
            }
        });
    }

    private AsyncTask<Void, Void, Void> runAsyncTask(AsyncTask<Void, Void, Void> task) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            return task.execute();
        }
    }

    private boolean validInput(android.text.Editable field) {
        //Peculiarly, isEmpty returns true when the field is filled
        return TextUtils.isEmpty(field);
    }


    @Override
    public void onBackPressed()
    {
        super.onBackPressed();
        Intent intent = new Intent(this, LoginActivity.class);
        this.startActivity(intent);
        this.finish();
    }



}





