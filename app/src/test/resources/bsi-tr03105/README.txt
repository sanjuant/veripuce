Jeu de référence officiel BSI TR-03105-5 (« ReferenceDataSet ») — fixtures de test.

Source : https://www.bsi.bund.de (Technische Richtlinie TR-03105 Teil 5, données de
référence publiques pour les tests de conformité ePassport). Téléchargé le 2026-07-10.

Contenu : EF_SOD.bin (SOD signé RSASSA-PSS SHA-256 par un DSC de TEST
« C=DE, O=HJP Consulting, CN=HJP PB DS ») + Datagroup1/2/14/15.bin.
Les SHA-256 de DG1, DG2 et DG14 correspondent aux empreintes signées du SOD
(vérifié indépendamment au dépôt) ; celui de DG15 NE correspond PAS — utilisé
comme cas négatif réel. La CSCA de test n'est pas fournie par le BSI : la
chaîne s'arrête au DSC (verdict attendu : VALID_UNTRUSTED).

Certificats et clés de TEST uniquement — rien de tout ceci ne doit jamais
approcher le magasin de production assets/csca/.
