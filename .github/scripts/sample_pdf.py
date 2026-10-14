"""Génère un PDF d'exemple de 3 pages (texte + cadres) pour le test sur émulateur."""
import sys

pages = [
    ["CONTRAT DE PRESTATION", "Entre la societe Ithamedia et le client.", "Article 1 - Objet du contrat",
     "Le prestataire s'engage a realiser les travaux decrits ci-dessous.", "Article 2 - Duree", "Le present contrat prend effet a la signature."],
    ["Article 3 - Prix", "Le montant total est de 2 400 euros HT.", "Article 4 - Paiement",
     "Paiement a 30 jours fin de mois.", "Article 5 - Confidentialite", "Les parties gardent les informations confidentielles."],
    ["Fait a Paris, le ....................", "", "Signature du prestataire", "", "", "Signature du client"],
]
objs = []
def add(s):
    objs.append(s)
    return len(objs)
font = add("<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>")
kids = []
page_ids = []
contents = []
for lines in pages:
    ops = ["0.85 0.82 0.75 RG 1 w 50 50 495 742 re S", "BT /F1 22 Tf 72 740 Td (%s) Tj ET" % lines[0]]
    y = 690
    for line in lines[1:]:
        ops.append("BT /F1 12 Tf 72 %d Td (%s) Tj ET" % (y, line))
        y -= 34
    stream = "\n".join(ops)
    contents.append(add("<< /Length %d >>\nstream\n%s\nendstream" % (len(stream), stream)))
pages_id = len(objs) + len(pages) + 1
for c in contents:
    page_ids.append(add("<< /Type /Page /Parent %d 0 R /MediaBox [0 0 595 842] /Resources << /Font << /F1 %d 0 R >> >> /Contents %d 0 R >>" % (pages_id, font, c)))
assert add("<< /Type /Pages /Kids [%s] /Count %d >>" % (" ".join("%d 0 R" % p for p in page_ids), len(page_ids))) == pages_id
catalog = add("<< /Type /Catalog /Pages %d 0 R >>" % pages_id)
out = bytearray(b"%PDF-1.4\n")
offsets = []
for i, o in enumerate(objs, 1):
    offsets.append(len(out))
    out += ("%d 0 obj\n%s\nendobj\n" % (i, o)).encode("latin-1")
xref = len(out)
out += ("xref\n0 %d\n0000000000 65535 f \n" % (len(objs) + 1)).encode()
for off in offsets:
    out += ("%010d 00000 n \n" % off).encode()
out += ("trailer\n<< /Size %d /Root %d 0 R >>\nstartxref\n%d\n%%%%EOF\n" % (len(objs) + 1, catalog, xref)).encode()
open(sys.argv[1], "wb").write(out)
