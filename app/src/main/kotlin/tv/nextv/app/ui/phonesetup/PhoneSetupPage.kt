package tv.nextv.app.ui.phonesetup

/** Pages HTML affichées sur le téléphone (formulaire, messages), lisibles sur un petit écran. */
internal object PhoneSetupPage {

    /** @param king onglet « King » (identifiant et mot de passe ; serveurs intégrés à l'application), choisi d'office. */
    fun form(error: String?, king: Boolean = false): String = page(
        """
        <h1>Ajouter une playlist</h1>
        <p class="hint">Elle sera envoyée à la télé, sur le même Wi-Fi.</p>
        ${error?.let { "<p class=\"error\">${escape(it)}</p>" }.orEmpty()}
        <form method="post">
          <div class="tabs">
            ${if (king) """<label><input type="radio" name="mode" value="king" checked onchange="show()"> King</label>""" else ""}
            <label><input type="radio" name="mode" value="xtream" ${if (king) "" else "checked"} onchange="show()"> Xtream</label>
            <label><input type="radio" name="mode" value="m3u" onchange="show()"> Lien M3U</label>
          </div>
          <label>Nom (facultatif)<input name="name" autocomplete="off" placeholder="Ma playlist"></label>
          <div id="account">
            <label id="server">Serveur<input name="server" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://serveur.com:8080"></label>
            <label>Identifiant<input name="username" autocapitalize="off" autocorrect="off" autocomplete="off"></label>
            <label>Mot de passe<input name="password" type="password" autocomplete="off"></label>
          </div>
          <div id="m3u" style="display:none">
            <label>Lien de la playlist<input name="url" inputmode="url" autocapitalize="off" autocorrect="off" placeholder="http://…/playlist.m3u"></label>
          </div>
          <button type="submit">Envoyer à la télé</button>
        </form>
        <script>
        function show(){
          var mode=document.querySelector('input[name=mode]:checked').value;
          document.getElementById('m3u').style.display=mode=='m3u'?'block':'none';
          document.getElementById('account').style.display=mode=='m3u'?'none':'block';
          document.getElementById('server').style.display=mode=='xtream'?'flex':'none';
        }
        show();
        </script>
        """,
    )

    fun message(title: String, text: String): String = page("<h1>${escape(title)}</h1><p>${escape(text)}</p>")

    private fun page(body: String): String = """
        <!doctype html><html lang="fr"><head><meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>NixTV</title>
        <style>
        body{margin:0;padding:24px 18px;font-family:system-ui,-apple-system,sans-serif;background:#0b1020;color:#eef1f8}
        h1{font-size:24px;margin:0 0 6px}
        p{line-height:1.5}
        .hint{color:#9aa4bd;margin-top:0}
        .error{background:#5a1d24;color:#ffd7dc;padding:10px 12px;border-radius:10px}
        form{display:flex;flex-direction:column;gap:14px;margin-top:18px}
        label{display:flex;flex-direction:column;gap:6px;font-size:15px;color:#c9d0e3}
        input{font-size:17px;padding:12px;border-radius:10px;border:1px solid #2c3550;background:#141b31;color:#fff}
        .tabs{display:flex;gap:10px}
        .tabs label{flex-direction:row;align-items:center;gap:8px;background:#141b31;padding:10px 12px;border-radius:10px;flex:1}
        .tabs input{width:auto;padding:0}
        button{font-size:18px;font-weight:600;padding:14px;border:0;border-radius:12px;background:#4f7cff;color:#fff;margin-top:6px}
        .brand{font-weight:800;letter-spacing:.5px;color:#7aa2ff;margin-bottom:18px}
        </style></head><body><div class="brand">NixTV</div>$body</body></html>
    """.trimIndent()

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
