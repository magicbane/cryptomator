/*******************************************************************************
 * Copyright (c) 2014 Sebastian Stenzel
 * This file is licensed under the terms of the MIT license.
 * See the LICENSE.txt file for more info.
 * 
 * Contributors:
 *     Sebastian Stenzel - initial API and implementation
 ******************************************************************************/
package org.cryptomator.crypto.aes256;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.CipherInputStream;
import javax.crypto.CipherOutputStream;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.Mac;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.DestroyFailedException;
import javax.security.auth.Destroyable;

import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.NullOutputStream;
import org.apache.commons.lang3.StringUtils;
import org.bouncycastle.crypto.generators.SCrypt;
import org.cryptomator.crypto.AbstractCryptor;
import org.cryptomator.crypto.CryptorIOSupport;
import org.cryptomator.crypto.exceptions.DecryptFailedException;
import org.cryptomator.crypto.exceptions.MacAuthenticationFailedException;
import org.cryptomator.crypto.exceptions.UnsupportedKeyLengthException;
import org.cryptomator.crypto.exceptions.WrongPasswordException;
import org.cryptomator.crypto.io.SeekableByteChannelInputStream;
import org.cryptomator.crypto.io.SeekableByteChannelOutputStream;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

public class Aes256Cryptor extends AbstractCryptor implements AesCryptographicConfiguration, FileNamingConventions {

	/**
	 * Defined in static initializer. Defaults to 256, but falls back to maximum value possible, if JCE Unlimited Strength Jurisdiction
	 * Policy Files isn't installed. Those files can be downloaded here: http://www.oracle.com/technetwork/java/javase/downloads/.
	 */
	private static final int AES_KEY_LENGTH_IN_BITS;

	/**
	 * PRNG for cryptographically secure random numbers. Defaults to SHA1-based number generator.
	 * 
	 * @see http://docs.oracle.com/javase/7/docs/technotes/guides/security/StandardNames.html#SecureRandom
	 */
	private final SecureRandom securePrng;

	/**
	 * Jackson JSON-Mapper.
	 */
	private final ObjectMapper objectMapper = new ObjectMapper();

	/**
	 * The decrypted master key. Its lifecycle starts with the construction of an Aes256Cryptor instance or
	 * {@link #decryptMasterKey(InputStream, CharSequence)}. Its lifecycle ends with {@link #swipeSensitiveData()}.
	 */
	private SecretKey primaryMasterKey;

	/**
	 * Decrypted secondary key used for hmac operations.
	 */
	private SecretKey hMacMasterKey;

	static {
		try {
			final int maxKeyLength = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			AES_KEY_LENGTH_IN_BITS = (maxKeyLength >= PREF_MASTER_KEY_LENGTH_IN_BITS) ? PREF_MASTER_KEY_LENGTH_IN_BITS : maxKeyLength;
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Algorithm should exist.", e);
		}
	}

	/**
	 * Creates a new Cryptor with a newly initialized PRNG.
	 */
	public Aes256Cryptor() {
		byte[] bytes = new byte[AES_KEY_LENGTH_IN_BITS / Byte.SIZE];
		try {
			securePrng = SecureRandom.getInstance(PRNG_ALGORITHM);
			securePrng.setSeed(securePrng.generateSeed(PRNG_SEED_LENGTH));
			securePrng.nextBytes(bytes);
			this.primaryMasterKey = new SecretKeySpec(bytes, AES_KEY_ALGORITHM);
			securePrng.nextBytes(bytes);
			this.hMacMasterKey = new SecretKeySpec(bytes, HMAC_KEY_ALGORITHM);
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("PRNG algorithm should exist.", e);
		} finally {
			Arrays.fill(bytes, (byte) 0);
		}
	}

	/**
	 * Encrypts the current masterKey with the given password and writes the result to the given output stream.
	 */
	@Override
	public void encryptMasterKey(OutputStream out, CharSequence password) throws IOException {
		try {
			// derive key:
			final byte[] kekSalt = randomData(SCRYPT_SALT_LENGTH);
			final SecretKey kek = scrypt(password, kekSalt, SCRYPT_COST_PARAM, SCRYPT_BLOCK_SIZE, AES_KEY_LENGTH_IN_BITS);

			// encrypt:
			final Cipher encCipher = aesKeyWrapCipher(kek, Cipher.WRAP_MODE);
			byte[] wrappedPrimaryKey = encCipher.wrap(primaryMasterKey);
			byte[] wrappedSecondaryKey = encCipher.wrap(hMacMasterKey);

			// save encrypted masterkey:
			final KeyFile keyfile = new KeyFile();
			keyfile.setScryptSalt(kekSalt);
			keyfile.setScryptCostParam(SCRYPT_COST_PARAM);
			keyfile.setScryptBlockSize(SCRYPT_BLOCK_SIZE);
			keyfile.setKeyLength(AES_KEY_LENGTH_IN_BITS);
			keyfile.setPrimaryMasterKey(wrappedPrimaryKey);
			keyfile.setHMacMasterKey(wrappedSecondaryKey);
			objectMapper.writeValue(out, keyfile);
		} catch (InvalidKeyException | IllegalBlockSizeException ex) {
			throw new IllegalStateException("Invalid hard coded configuration.", ex);
		}
	}

	/**
	 * Reads the encrypted masterkey from the given input stream and decrypts it with the given password.
	 * 
	 * @throws DecryptFailedException If the decryption failed for various reasons (including wrong password).
	 * @throws WrongPasswordException If the provided password was wrong. Note: Sometimes the algorithm itself fails due to a wrong
	 *             password. In this case a DecryptFailedException will be thrown.
	 * @throws UnsupportedKeyLengthException If the masterkey has been encrypted with a higher key length than supported by the system. In
	 *             this case Java JCE needs to be installed.
	 */
	@Override
	public void decryptMasterKey(InputStream in, CharSequence password) throws DecryptFailedException, WrongPasswordException, UnsupportedKeyLengthException, IOException {
		try {
			// load encrypted masterkey:
			final KeyFile keyfile = objectMapper.readValue(in, KeyFile.class);

			// check, whether the key length is supported:
			final int maxKeyLen = Cipher.getMaxAllowedKeyLength(AES_KEY_ALGORITHM);
			if (keyfile.getKeyLength() > maxKeyLen) {
				throw new UnsupportedKeyLengthException(keyfile.getKeyLength(), maxKeyLen);
			}

			// derive key:
			final SecretKey kek = scrypt(password, keyfile.getScryptSalt(), keyfile.getScryptCostParam(), keyfile.getScryptBlockSize(), AES_KEY_LENGTH_IN_BITS);

			// decrypt and check password by catching AEAD exception
			final Cipher decCipher = aesKeyWrapCipher(kek, Cipher.UNWRAP_MODE);
			SecretKey primary = (SecretKey) decCipher.unwrap(keyfile.getPrimaryMasterKey(), AES_KEY_ALGORITHM, Cipher.SECRET_KEY);
			SecretKey secondary = (SecretKey) decCipher.unwrap(keyfile.getHMacMasterKey(), HMAC_KEY_ALGORITHM, Cipher.SECRET_KEY);

			// everything ok, assign decrypted keys:
			this.primaryMasterKey = primary;
			this.hMacMasterKey = secondary;
		} catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("Algorithm should exist.", ex);
		} catch (InvalidKeyException e) {
			throw new WrongPasswordException();
		}
	}

	@Override
	public void swipeSensitiveDataInternal() {
		destroyQuietly(primaryMasterKey);
		destroyQuietly(hMacMasterKey);
	}

	private void destroyQuietly(Destroyable d) {
		try {
			d.destroy();
		} catch (DestroyFailedException e) {
			// ignore
		}
	}

	private Cipher aesKeyWrapCipher(SecretKey key, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_KEYWRAP_CIPHER);
			cipher.init(cipherMode, key);
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist and accept GCM specs.", ex);
		}
	}

	private Cipher aesCtrCipher(SecretKey key, byte[] iv, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_CTR_CIPHER);
			cipher.init(cipherMode, key, new IvParameterSpec(iv));
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException | InvalidAlgorithmParameterException ex) {
			throw new IllegalStateException("Algorithm/Padding should exist and accept an IV.", ex);
		}
	}

	private Cipher aesEcbCipher(SecretKey key, int cipherMode) {
		try {
			final Cipher cipher = Cipher.getInstance(AES_ECB_CIPHER);
			cipher.init(cipherMode, key);
			return cipher;
		} catch (InvalidKeyException ex) {
			throw new IllegalArgumentException("Invalid key.", ex);
		} catch (NoSuchAlgorithmException | NoSuchPaddingException ex) {
			throw new AssertionError("Every implementation of the Java platform is required to support AES/ECB/PKCS5Padding.", ex);
		}

	}

	private Mac hmacSha256(SecretKey key) {
		try {
			final Mac mac = Mac.getInstance(HMAC_KEY_ALGORITHM);
			mac.init(key);
			return mac;
		} catch (NoSuchAlgorithmException e) {
			throw new AssertionError("Every implementation of the Java platform is required to support HmacSHA256.", e);
		} catch (InvalidKeyException e) {
			throw new IllegalArgumentException("Invalid key", e);
		}
	}

	private byte[] randomData(int length) {
		final byte[] result = new byte[length];
		securePrng.nextBytes(result);
		return result;
	}

	private SecretKey scrypt(CharSequence password, byte[] salt, int costParam, int blockSize, int keyLengthInBits) {
		// use sb, as password.toString's implementation is unknown
		final StringBuilder sb = new StringBuilder(password);
		final byte[] pw = sb.toString().getBytes();
		try {
			final byte[] key = SCrypt.generate(pw, salt, costParam, blockSize, 1, keyLengthInBits / Byte.SIZE);
			return new SecretKeySpec(key, AES_KEY_ALGORITHM);
		} finally {
			// destroy copied bytes of the plaintext password:
			Arrays.fill(pw, (byte) 0);
			for (int i = 0; i < password.length(); i++) {
				sb.setCharAt(i, (char) 0);
			}
		}
	}

	@Override
	public String encryptPath(String cleartextPath, char encryptedPathSep, char cleartextPathSep, CryptorIOSupport ioSupport) {
		try {
			final String[] cleartextPathComps = StringUtils.split(cleartextPath, cleartextPathSep);
			final List<String> encryptedPathComps = new ArrayList<>(cleartextPathComps.length);
			for (final String cleartext : cleartextPathComps) {
				final String encrypted = encryptPathComponent(cleartext, primaryMasterKey, hMacMasterKey, ioSupport);
				encryptedPathComps.add(encrypted);
			}
			return StringUtils.join(encryptedPathComps, encryptedPathSep);
		} catch (InvalidKeyException | IOException e) {
			throw new IllegalStateException("Unable to encrypt path: " + cleartextPath, e);
		}
	}

	/**
	 * Each path component, i.e. file or directory name separated by path separators, gets encrypted for its own.<br/>
	 * Encryption will blow up the filename length due to aes block sizes and base32 encoding. The result may be too long for some old file
	 * systems.<br/>
	 * This means that we need a workaround for filenames longer than the limit defined in
	 * {@link FileNamingConventions#ENCRYPTED_FILENAME_LENGTH_LIMIT}.<br/>
	 * <br/>
	 * In any case we will create the encrypted filename normally. For those, that are too long, we calculate a checksum. No
	 * cryptographically secure hash is needed here. We just want an uniform distribution for better load balancing. All encrypted filenames
	 * with the same checksum will then share a metadata file, in which a lookup map between encrypted filenames and short unique
	 * alternative names are stored.<br/>
	 * <br/>
	 * These alternative names consist of the checksum, a unique id and a special file extension defined in
	 * {@link FileNamingConventions#LONG_NAME_FILE_EXT}.
	 */
	private String encryptPathComponent(final String cleartext, final SecretKey aesKey, final SecretKey macKey, CryptorIOSupport ioSupport) throws IOException, InvalidKeyException {
		final byte[] cleartextBytes = cleartext.getBytes(StandardCharsets.UTF_8);

		// encrypt:
		final byte[] encryptedBytes = AesSivCipherUtil.sivEncrypt(aesKey, macKey, cleartextBytes);
		final String ivAndCiphertext = ENCRYPTED_FILENAME_CODEC.encodeAsString(encryptedBytes);

		if (ivAndCiphertext.length() + BASIC_FILE_EXT.length() > ENCRYPTED_FILENAME_LENGTH_LIMIT) {
			final String groupPrefix = ivAndCiphertext.substring(0, LONG_NAME_PREFIX_LENGTH);
			final String metadataFilename = groupPrefix + METADATA_FILE_EXT;
			final LongFilenameMetadata metadata = this.getMetadata(ioSupport, metadataFilename);
			final String alternativeFileName = groupPrefix + metadata.getOrCreateUuidForEncryptedFilename(ivAndCiphertext).toString() + LONG_NAME_FILE_EXT;
			this.storeMetadata(ioSupport, metadataFilename, metadata);
			return alternativeFileName;
		} else {
			return ivAndCiphertext + BASIC_FILE_EXT;
		}
	}

	@Override
	public String decryptPath(String encryptedPath, char encryptedPathSep, char cleartextPathSep, CryptorIOSupport ioSupport) throws DecryptFailedException {
		try {
			final String[] encryptedPathComps = StringUtils.split(encryptedPath, encryptedPathSep);
			final List<String> cleartextPathComps = new ArrayList<>(encryptedPathComps.length);
			for (final String encrypted : encryptedPathComps) {
				final String cleartext = decryptPathComponent(encrypted, primaryMasterKey, hMacMasterKey, ioSupport);
				cleartextPathComps.add(new String(cleartext));
			}
			return StringUtils.join(cleartextPathComps, cleartextPathSep);
		} catch (InvalidKeyException | IOException e) {
			throw new IllegalStateException("Unable to decrypt path: " + encryptedPath, e);
		}
	}

	/**
	 * @see #encryptPathComponent(String, SecretKey, CryptorIOSupport)
	 */
	private String decryptPathComponent(final String encrypted, final SecretKey aesKey, final SecretKey macKey, CryptorIOSupport ioSupport) throws IOException, InvalidKeyException, DecryptFailedException {
		final String ciphertext;
		if (encrypted.endsWith(LONG_NAME_FILE_EXT)) {
			final String basename = StringUtils.removeEnd(encrypted, LONG_NAME_FILE_EXT);
			final String groupPrefix = basename.substring(0, LONG_NAME_PREFIX_LENGTH);
			final String uuid = basename.substring(LONG_NAME_PREFIX_LENGTH);
			final String metadataFilename = groupPrefix + METADATA_FILE_EXT;
			final LongFilenameMetadata metadata = this.getMetadata(ioSupport, metadataFilename);
			ciphertext = metadata.getEncryptedFilenameForUUID(UUID.fromString(uuid));
		} else if (encrypted.endsWith(BASIC_FILE_EXT)) {
			ciphertext = StringUtils.removeEndIgnoreCase(encrypted, BASIC_FILE_EXT);
		} else {
			throw new IllegalArgumentException("Unsupported path component: " + encrypted);
		}

		// decrypt:
		final byte[] encryptedBytes = ENCRYPTED_FILENAME_CODEC.decode(ciphertext);
		final byte[] cleartextBytes = AesSivCipherUtil.sivDecrypt(aesKey, macKey, encryptedBytes);

		return new String(cleartextBytes, StandardCharsets.UTF_8);
	}

	private LongFilenameMetadata getMetadata(CryptorIOSupport ioSupport, String metadataFile) throws IOException {
		final byte[] fileContent = ioSupport.readPathSpecificMetadata(metadataFile);
		if (fileContent == null) {
			return new LongFilenameMetadata();
		} else {
			return objectMapper.readValue(fileContent, LongFilenameMetadata.class);
		}
	}

	private void storeMetadata(CryptorIOSupport ioSupport, String metadataFile, LongFilenameMetadata metadata) throws JsonProcessingException, IOException {
		ioSupport.writePathSpecificMetadata(metadataFile, objectMapper.writeValueAsBytes(metadata));
	}

	@Override
	public Long decryptedContentLength(SeekableByteChannel encryptedFile) throws IOException {
		// skip 128bit IV + 256 bit MAC:
		encryptedFile.position(48);

		// read encrypted value:
		final ByteBuffer encryptedFileSizeBuffer = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		final int numFileSizeBytesRead = encryptedFile.read(encryptedFileSizeBuffer);

		// return "unknown" value, if EOF
		if (numFileSizeBytesRead != encryptedFileSizeBuffer.capacity()) {
			return null;
		}

		// decrypt size:
		try {
			final Cipher sizeCipher = aesEcbCipher(primaryMasterKey, Cipher.DECRYPT_MODE);
			final byte[] decryptedFileSize = sizeCipher.doFinal(encryptedFileSizeBuffer.array());
			final ByteBuffer fileSizeBuffer = ByteBuffer.wrap(decryptedFileSize);
			return fileSizeBuffer.getLong();
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			throw new IllegalStateException(e);
		}
	}

	private void encryptedContentLength(SeekableByteChannel encryptedFile, Long contentLength) throws IOException {
		final ByteBuffer encryptedFileSizeBuffer;

		// encrypt content length in ECB mode (content length is less than one block):
		try {
			final ByteBuffer fileSizeBuffer = ByteBuffer.allocate(Long.BYTES);
			fileSizeBuffer.putLong(contentLength);
			final Cipher sizeCipher = aesEcbCipher(primaryMasterKey, Cipher.ENCRYPT_MODE);
			final byte[] encryptedFileSize = sizeCipher.doFinal(fileSizeBuffer.array());
			encryptedFileSizeBuffer = ByteBuffer.wrap(encryptedFileSize);
		} catch (IllegalBlockSizeException | BadPaddingException e) {
			throw new IllegalStateException("Block size must be valid, as padding is requested. BadPaddingException not possible in encrypt mode.", e);
		}

		// skip 128bit IV + 256 bit MAC:
		encryptedFile.position(48);

		// write result:
		encryptedFile.write(encryptedFileSizeBuffer);
	}

	@Override
	public boolean isAuthentic(SeekableByteChannel encryptedFile) throws IOException {
		// init mac:
		final Mac calculatedMac = this.hmacSha256(hMacMasterKey);

		// read stored mac:
		encryptedFile.position(16);
		final ByteBuffer storedMac = ByteBuffer.allocate(calculatedMac.getMacLength());
		final int numMacBytesRead = encryptedFile.read(storedMac);

		// check validity of header:
		if (numMacBytesRead != calculatedMac.getMacLength()) {
			throw new IOException("Failed to read file header.");
		}

		// go to begin of content:
		encryptedFile.position(64);

		// calculated MAC
		final InputStream in = new SeekableByteChannelInputStream(encryptedFile);
		final InputStream macIn = new MacInputStream(in, calculatedMac);
		IOUtils.copyLarge(macIn, new NullOutputStream());

		// compare (in constant time):
		return MessageDigest.isEqual(storedMac.array(), calculatedMac.doFinal());
	}

	@Override
	public Long decryptFile(SeekableByteChannel encryptedFile, OutputStream plaintextFile) throws IOException, DecryptFailedException {
		// read iv:
		encryptedFile.position(0);
		final ByteBuffer countingIv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		final int numIvBytesRead = encryptedFile.read(countingIv);

		// init mac:
		final Mac calculatedMac = this.hmacSha256(hMacMasterKey);

		// read stored mac:
		final ByteBuffer storedMac = ByteBuffer.allocate(calculatedMac.getMacLength());
		final int numMacBytesRead = encryptedFile.read(storedMac);

		// read file size:
		final Long fileSize = decryptedContentLength(encryptedFile);

		// check validity of header:
		if (numIvBytesRead != AES_BLOCK_LENGTH || numMacBytesRead != calculatedMac.getMacLength() || fileSize == null) {
			throw new IOException("Failed to read file header.");
		}

		// go to begin of content:
		encryptedFile.position(64);

		// generate cipher:
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.DECRYPT_MODE);

		// read content
		final InputStream in = new SeekableByteChannelInputStream(encryptedFile);
		final InputStream macIn = new MacInputStream(in, calculatedMac);
		final InputStream cipheredIn = new CipherInputStream(macIn, cipher);
		final long bytesDecrypted = IOUtils.copyLarge(cipheredIn, plaintextFile, 0, fileSize);

		// drain remaining bytes to /dev/null to complete MAC calculation:
		IOUtils.copyLarge(macIn, new NullOutputStream());

		// compare (in constant time):
		final boolean macMatches = MessageDigest.isEqual(storedMac.array(), calculatedMac.doFinal());
		if (!macMatches) {
			// This exception will be thrown AFTER we sent the decrypted content to the user.
			// This has two advantages:
			// - we don't need to read files twice
			// - we can still restore files suffering from non-malicious bit rotting
			// Anyway me MUST make sure to warn the user. This will be done by the UI when catching this exception.
			throw new MacAuthenticationFailedException("MAC authentication failed.");
		}

		return bytesDecrypted;
	}

	@Override
	public Long decryptRange(SeekableByteChannel encryptedFile, OutputStream plaintextFile, long pos, long length) throws IOException, DecryptFailedException {
		// read iv:
		encryptedFile.position(0);
		final ByteBuffer countingIv = ByteBuffer.allocate(AES_BLOCK_LENGTH);
		final int numIvBytesRead = encryptedFile.read(countingIv);

		// check validity of header:
		if (numIvBytesRead != AES_BLOCK_LENGTH) {
			throw new IOException("Failed to read file header.");
		}

		// seek relevant position and update iv:
		long firstRelevantBlock = pos / AES_BLOCK_LENGTH; // cut of fraction!
		long beginOfFirstRelevantBlock = firstRelevantBlock * AES_BLOCK_LENGTH;
		long offsetInsideFirstRelevantBlock = pos - beginOfFirstRelevantBlock;
		countingIv.putLong(AES_BLOCK_LENGTH - Long.BYTES, firstRelevantBlock);

		// fast forward stream:
		encryptedFile.position(64 + beginOfFirstRelevantBlock);

		// generate cipher:
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.DECRYPT_MODE);

		// read content
		final InputStream in = new SeekableByteChannelInputStream(encryptedFile);
		final InputStream cipheredIn = new CipherInputStream(in, cipher);
		return IOUtils.copyLarge(cipheredIn, plaintextFile, offsetInsideFirstRelevantBlock, length);
	}

	@Override
	public Long encryptFile(InputStream plaintextFile, SeekableByteChannel encryptedFile) throws IOException {
		// truncate file
		encryptedFile.truncate(0);

		// use an IV, whose last 8 bytes store a long used in counter mode and write initial value to file.
		final ByteBuffer countingIv = ByteBuffer.wrap(randomData(AES_BLOCK_LENGTH));
		countingIv.putLong(AES_BLOCK_LENGTH - Long.BYTES, 0l);
		encryptedFile.write(countingIv);

		// init crypto stuff:
		final Mac mac = this.hmacSha256(hMacMasterKey);
		final Cipher cipher = this.aesCtrCipher(primaryMasterKey, countingIv.array(), Cipher.ENCRYPT_MODE);

		// init mac buffer and skip 32 bytes
		final ByteBuffer macBuffer = ByteBuffer.allocate(mac.getMacLength());
		encryptedFile.write(macBuffer);

		// encrypt and write "zero length" as a placeholder, which will be read by concurrent requests, as long as encryption didn't finish:
		encryptedContentLength(encryptedFile, 0l);

		// write content:
		final OutputStream out = new SeekableByteChannelOutputStream(encryptedFile);
		final OutputStream macOut = new MacOutputStream(out, mac);
		final OutputStream cipheredOut = new CipherOutputStream(macOut, cipher);
		final OutputStream blockSizeBufferedOut = new BufferedOutputStream(cipheredOut, AES_BLOCK_LENGTH);
		final Long plaintextSize = IOUtils.copyLarge(plaintextFile, blockSizeBufferedOut);

		// ensure total byte count is a multiple of the block size, in CTR mode:
		final int remainderToFillLastBlock = AES_BLOCK_LENGTH - (int) (plaintextSize % AES_BLOCK_LENGTH);
		blockSizeBufferedOut.write(new byte[remainderToFillLastBlock]);

		// append a few blocks of fake data:
		final int numberOfPlaintextBlocks = (int) Math.ceil(plaintextSize / AES_BLOCK_LENGTH);
		final int upToTenPercentFakeBlocks = (int) Math.ceil(Math.random() * 0.1 * numberOfPlaintextBlocks);
		final byte[] emptyBytes = new byte[AES_BLOCK_LENGTH];
		for (int i = 0; i < upToTenPercentFakeBlocks; i += AES_BLOCK_LENGTH) {
			blockSizeBufferedOut.write(emptyBytes);
		}
		blockSizeBufferedOut.flush();

		// write MAC of total ciphertext:
		macBuffer.clear();
		macBuffer.put(mac.doFinal());
		macBuffer.flip();
		encryptedFile.position(16); // right behind the IV
		encryptedFile.write(macBuffer); // 256 bit MAC

		// encrypt and write plaintextSize:
		encryptedContentLength(encryptedFile, plaintextSize);

		return plaintextSize;
	}

	@Override
	public Filter<Path> getPayloadFilesFilter() {
		return new Filter<Path>() {
			@Override
			public boolean accept(Path entry) throws IOException {
				return ENCRYPTED_FILE_GLOB_MATCHER.matches(entry);
			}
		};
	}

}
